package com.praful.filehandler.transcription;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.praful.filehandler.upload.TranscriptSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Embedded transcription using the whisper.cpp command line binary.
 *
 * <p>The binary and a quantized model are baked into the Docker image, so no
 * model download happens at runtime (Render's free filesystem is ephemeral).
 * Each call runs in its own temp directory which is deleted afterwards to keep
 * the container's memory and disk footprint small.
 */
@Component
public class WhisperCppEngine implements TranscriptionEngine {

    private static final Logger log = LoggerFactory.getLogger(WhisperCppEngine.class);

    private final FfmpegAudioNormalizer normalizer;
    private final ObjectMapper objectMapper;
    private final String binary;
    private final String model;
    private final int threads;

    public WhisperCppEngine(FfmpegAudioNormalizer normalizer,
                            ObjectMapper objectMapper,
                            @Value("${app.whisper.binary:/opt/whisper/whisper-cli}") String binary,
                            @Value("${app.whisper.model:/opt/models/ggml-tiny-q5_1.bin}") String model,
                            @Value("${app.whisper.threads:1}") int threads) {
        this.normalizer = normalizer;
        this.objectMapper = objectMapper;
        this.binary = binary;
        this.model = model;
        this.threads = threads;
    }

    @Override
    public String name() {
        return "whisper.cpp";
    }

    @Override
    public EngineResult transcribe(Path audio, String language) throws Exception {
        if (!Files.isRegularFile(Path.of(binary))) {
            throw new IllegalStateException("whisper.cpp binary not found at " + binary);
        }
        if (!Files.isRegularFile(Path.of(model))) {
            throw new IllegalStateException("Whisper model not found at " + model);
        }

        Path workDir = Files.createTempDirectory("whisper-" + UUID.randomUUID());
        try {
            Path wav = normalizer.toWhisperWav(audio, workDir);
            Path outPrefix = workDir.resolve("out");

            List<String> command = new ArrayList<>(List.of(
                    binary, "-m", model,
                    "-f", wav.toString(),
                    "-oj", "-of", outPrefix.toString(),
                    "-t", String.valueOf(threads)));
            if (language != null && !language.isBlank()) {
                command.add("-l");
                command.add(language);
            }

            run(command);

            Path json = workDir.resolve("out.json");
            if (!Files.isRegularFile(json)) {
                throw new IOException("whisper.cpp did not produce JSON output");
            }
            return parse(Files.readString(json, StandardCharsets.UTF_8));
        } finally {
            deleteRecursively(workDir);
        }
    }

    private void run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("whisper.cpp failed (exit " + exitCode + "): " + lastLine(output));
        }
    }

    private EngineResult parse(String json) throws IOException {
        WhisperJson parsed = objectMapper.readValue(json, WhisperJson.class);
        String language = parsed.result() == null ? null : parsed.result().language();

        List<TranscriptSegment> segments = new ArrayList<>();
        StringBuilder text = new StringBuilder();

        if (parsed.transcription() != null) {
            for (WhisperJson.Transcription item : parsed.transcription()) {
                if (item == null || item.text() == null || item.text().isBlank()) {
                    continue;
                }
                String segmentText = item.text().trim();
                Double start = null;
                Double end = null;
                if (item.offsets() != null) {
                    if (item.offsets().from() != null) start = item.offsets().from() / 1000.0;
                    if (item.offsets().to() != null) end = item.offsets().to() / 1000.0;
                }
                segments.add(new TranscriptSegment(start, end, segmentText));
                if (text.length() > 0) text.append(' ');
                text.append(segmentText);
            }
        }
        return new EngineResult(segments, text.toString(), language);
    }

    private static String lastLine(String output) {
        String[] lines = output.split("\\R");
        return lines.length == 0 ? "" : lines[lines.length - 1];
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException e) {
            log.debug("Could not clean temp dir {}: {}", dir, e.getMessage());
        }
    }

    /** Shape of whisper.cpp's {@code -oj} output (unknown fields ignored). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WhisperJson(Result result, List<Transcription> transcription) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Result(String language) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Transcription(Offsets offsets, String text) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Offsets(Long from, Long to) {
        }
    }
}
