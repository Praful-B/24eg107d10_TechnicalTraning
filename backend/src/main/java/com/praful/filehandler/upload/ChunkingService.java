package com.praful.filehandler.upload;

import com.praful.filehandler.rabbitmq.PreprocessMessageDto;
import com.praful.filehandler.rabbitmq.RabbitMQConfiguration;
import com.praful.filehandler.rabbitmq.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits uploaded audio into chunks for parallel transcription.
 *
 * <ol>
 *   <li>Converts to 16 kHz mono WAV (Whisper's preferred format)</li>
 *   <li>Detects silences using FFmpeg's silencedetect filter</li>
 *   <li>Cuts on silence midpoints near the target chunk length</li>
 *   <li>Publishes each chunk as a separate RabbitMQ message</li>
 * </ol>
 *
 * Each chunk overlaps its predecessor slightly so words at boundaries are not lost.
 */
@Service
public class ChunkingService {

    private static final Logger log = LoggerFactory.getLogger(ChunkingService.class);

    // FFmpeg silence detection patterns
    private static final Pattern SILENCE_START = Pattern.compile("silence_start:\\s*(-?[0-9.]+)");
    private static final Pattern SILENCE_END = Pattern.compile("silence_end:\\s*(-?[0-9.]+)");

    private final UploadRepository uploadRepository;
    private final RabbitTemplate rabbitTemplate;
    private final JobEventService jobEventService;

    private final String ffmpegPath;
    private final String ffprobePath;
    private final double targetSeconds;
    private final double silenceNoiseDb;
    private final double silenceMinDuration;
    private final double overlapSeconds;
    private final Path storageDir;

    public ChunkingService(UploadRepository uploadRepository,
                           RabbitTemplate rabbitTemplate,
                           JobEventService jobEventService,
                           @Value("${app.ffmpeg.path:ffmpeg}") String ffmpegPath,
                           @Value("${app.ffprobe.path:ffprobe}") String ffprobePath,
                           @Value("${app.chunk.target-seconds:600}") double targetSeconds,
                           @Value("${app.chunk.silence-noise-db:-35}") double silenceNoiseDb,
                           @Value("${app.chunk.silence-min-duration:0.5}") double silenceMinDuration,
                           @Value("${app.chunk.overlap-seconds:1.0}") double overlapSeconds,
                           @Value("${app.storage.dir}") Path storageDir) {
        this.uploadRepository = uploadRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.jobEventService = jobEventService;
        this.ffmpegPath = ffmpegPath;
        this.ffprobePath = ffprobePath;
        this.targetSeconds = targetSeconds;
        this.silenceNoiseDb = silenceNoiseDb;
        this.silenceMinDuration = silenceMinDuration;
        this.overlapSeconds = overlapSeconds;
        this.storageDir = storageDir;
    }

    /**
     * Process an uploaded file: normalize, detect silences, split into chunks.
     * Runs asynchronously on the chunk executor pool.
     */
    @Async("chunkExecutor")
    public void process(UUID jobId) {
        Upload job = uploadRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Chunking requested for unknown job {}", jobId);
            return;
        }

        Path inputFile = toPath(job.getFilepath());
        if (inputFile == null || !Files.exists(inputFile)) {
            fail(job, "Uploaded file is missing");
            return;
        }

        try {
            Path jobDir = createJobDirectory(jobId);
            job.setChunkDir(jobDir.toString());

            Path normalized = normalizeAudio(inputFile, jobDir);
            double duration = probeDuration(normalized);
            List<double[]> silences = detectSilences(normalized);
            List<double[]> ranges = planCuts(duration, silences);

            int totalChunks = ranges.size();
            job.setTotalChunks(totalChunks);
            job.setChunksReceived(0);
            job.setStatus(MessageStatus.PREPARING);
            uploadRepository.save(job);

            for (int i = 0; i < totalChunks; i++) {
                double[] range = ranges.get(i);
                double chunkStart = range[0];
                double chunkEnd = range[1];
                double extractStart = i == 0 ? chunkStart : Math.max(0, chunkStart - overlapSeconds);

                Path chunkFile = extractChunk(normalized, extractStart, chunkEnd, jobDir, i);
                publishChunk(job, chunkFile, i, totalChunks, extractStart, chunkEnd, chunkEnd - extractStart, 0);
            }

            log.info("Job {} split into {} chunk(s), target ~{}s each", jobId, totalChunks, targetSeconds);
            jobEventService.publish(JobEvent.from(job));

        } catch (Exception e) {
            log.error("Chunking failed for job {}", jobId, e);
            fail(job, "Could not prepare audio: " + e.getMessage());
        }
    }

    private Path createJobDirectory(UUID jobId) throws IOException {
        Path jobDir = storageDir.resolve(jobId.toString());
        Files.createDirectories(jobDir);
        return jobDir;
    }

    private Path normalizeAudio(Path input, Path outputDir) throws IOException, InterruptedException {
        Path normalized = outputDir.resolve("normalized.wav");
        runProcess(List.of(
                ffmpegPath, "-hide_banner", "-loglevel", "error", "-y",
                "-i", input.toString(),
                "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le",
                normalized.toString()));
        return normalized;
    }

    private Path extractChunk(Path normalized, double extractStart, double chunkEnd,
                              Path outputDir, int index) throws IOException, InterruptedException {
        Path chunkFile = outputDir.resolve(String.format("chunk_%03d.wav", index));
        runProcess(List.of(
                ffmpegPath, "-hide_banner", "-loglevel", "error", "-y",
                "-ss", formatSeconds(extractStart),
                "-t", formatSeconds(chunkEnd - extractStart),
                "-i", normalized.toString(),
                "-c:a", "copy",
                chunkFile.toString()));
        return chunkFile;
    }

    private void publishChunk(Upload job, Path chunkFile, int index, int total,
                              double start, double end, double duration, int attempt) {
        rabbitTemplate.convertAndSend(
                RabbitMQConfiguration.EXCHANGE_NAME,
                RabbitMQConfiguration.PREPROCESSING_ROUTING_KEY,
                new PreprocessMessageDto(
                        job.getJobId(),
                        chunkFile.toString(),
                        Status.SAVED,
                        index,
                        total,
                        start,
                        end,
                        duration,
                        job.getLanguage(),
                        attempt));
    }

    private void fail(Upload job, String reason) {
        job.setStatus(MessageStatus.FAILED);
        job.setFailureReason(reason);
        uploadRepository.save(job);
        jobEventService.publish(JobEvent.from(job));
    }

    // ------------------------------------------------------------------
    // Silence-based cut planning
    // ------------------------------------------------------------------

    /**
     * Plan cut points near silence midpoints, aiming for target chunk length.
     * The last chunk takes whatever remains.
     */
    List<double[]> planCuts(double duration, List<double[]> silences) {
        List<double[]> ranges = new ArrayList<>();
        if (duration <= 0) {
            ranges.add(new double[]{0, 0});
            return ranges;
        }

        double position = 0;
        // Keep tail > 0.6 * target to avoid tiny final chunks
        while (duration - position > targetSeconds * 1.2) {
            double idealCut = position + targetSeconds;
            double cut = findNearestSilenceMidpoint(silences, idealCut,
                    position + targetSeconds * 0.5,
                    position + targetSeconds * 1.4);
            if (cut <= position) {
                cut = idealCut; // No suitable silence, cut at target
            }
            ranges.add(new double[]{position, cut});
            position = cut;
        }
        ranges.add(new double[]{position, duration});
        return ranges;
    }

    private double findNearestSilenceMidpoint(List<double[]> silences, double ideal,
                                               double searchLow, double searchHigh) {
        double best = -1;
        double bestDistance = Double.MAX_VALUE;

        for (double[] silence : silences) {
            double midpoint = (silence[0] + silence[1]) / 2;
            if (midpoint <= searchLow || midpoint >= searchHigh) continue;

            double distance = Math.abs(midpoint - ideal);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = midpoint;
            }
        }
        return best;
    }

    private List<double[]> detectSilences(Path input) throws IOException, InterruptedException {
        String output = runAndCapture(List.of(
                ffmpegPath, "-hide_banner", "-nostats", "-i", input.toString(),
                "-af", "silencedetect=noise=" + silenceNoiseDb + "dB:d=" + silenceMinDuration,
                "-f", "null", "-"));

        List<double[]> silences = new ArrayList<>();
        double pendingStart = -1;

        for (String line : output.split("\\R")) {
            Matcher startMatcher = SILENCE_START.matcher(line);
            if (startMatcher.find()) {
                pendingStart = Double.parseDouble(startMatcher.group(1));
            }

            Matcher endMatcher = SILENCE_END.matcher(line);
            if (endMatcher.find() && pendingStart >= 0) {
                double endTime = Double.parseDouble(endMatcher.group(1));
                if (endTime > pendingStart) {
                    silences.add(new double[]{pendingStart, endTime});
                }
                pendingStart = -1;
            }
        }
        return silences;
    }

    private double probeDuration(Path input) throws IOException, InterruptedException {
        String output = runAndCapture(List.of(
                ffprobePath, "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                input.toString())).trim();

        try {
            return Double.parseDouble(output);
        } catch (NumberFormatException e) {
            throw new IOException("Could not parse audio duration: " + output);
        }
    }

    // ------------------------------------------------------------------
    // Process execution helpers
    // ------------------------------------------------------------------

    private void runProcess(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new IOException(command.get(0) + " failed (exit " + exitCode + "): " + lastLine(output));
        }
    }

    private String runAndCapture(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        return output;
    }

    private static String lastLine(String output) {
        String[] lines = output.split("\\R");
        return lines.length == 0 ? "" : lines[lines.length - 1];
    }

    private static String formatSeconds(double seconds) {
        return String.format(java.util.Locale.ROOT, "%.3f", seconds);
    }

    private static Path toPath(String path) {
        return path == null ? null : Path.of(path);
    }
}
