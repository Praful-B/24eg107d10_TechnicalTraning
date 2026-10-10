package com.praful.filehandler.transcription;

import com.praful.filehandler.upload.JobEvent;
import com.praful.filehandler.upload.JobEventService;
import com.praful.filehandler.upload.MessageStatus;
import com.praful.filehandler.upload.TranscriptSegment;
import com.praful.filehandler.upload.Upload;
import com.praful.filehandler.upload.UploadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Synchronous (queue-free) transcription.
 *
 * <p>Instead of publishing chunks to RabbitMQ, this service transcribes the whole
 * uploaded file in-process on a single background thread and writes the result
 * straight onto the job. The engine is chosen by {@code app.transcription.mode}:
 * the embedded {@code whispercpp} binary, or a hosted {@code api}. If whisper.cpp
 * fails (for example an out-of-memory kill on a tiny instance) and a key is
 * configured, it retries through the API when {@code fallback-to-api} is enabled.
 *
 * <p>The frontend needs no changes: it already reacts to the {@code PREPARING ->
 * PROCESSING -> COMPLETED} transitions pushed over SSE.
 */
@Service
public class LocalTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(LocalTranscriptionService.class);

    private final UploadRepository uploadRepository;
    private final JobEventService jobEventService;
    private final WhisperCppEngine whisperCppEngine;
    private final OpenAiCompatibleApiEngine apiEngine;
    private final ObjectMapper objectMapper;
    private final String mode;
    private final boolean fallbackToApi;

    public LocalTranscriptionService(UploadRepository uploadRepository,
                                     JobEventService jobEventService,
                                     WhisperCppEngine whisperCppEngine,
                                     OpenAiCompatibleApiEngine apiEngine,
                                     ObjectMapper objectMapper,
                                     @Value("${app.transcription.mode:queue}") String mode,
                                     @Value("${app.transcription.fallback-to-api:false}") boolean fallbackToApi) {
        this.uploadRepository = uploadRepository;
        this.jobEventService = jobEventService;
        this.whisperCppEngine = whisperCppEngine;
        this.apiEngine = apiEngine;
        this.objectMapper = objectMapper;
        this.mode = mode;
        this.fallbackToApi = fallbackToApi;
    }

    @Async("transcriptionExecutor")
    public void process(UUID jobId) {
        Upload job = uploadRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Transcription requested for unknown job {}", jobId);
            return;
        }

        try {
            Path audio = toPath(job.getFilepath());
            if (audio == null || !Files.isRegularFile(audio)) {
                fail(job, "Uploaded file is missing");
                return;
            }

            job.setStatus(MessageStatus.PREPARING);
            job.setFailureReason(null);
            job.setChunksReceived(0);
            uploadRepository.save(job);
            jobEventService.publish(JobEvent.from(job));

            job.setStatus(MessageStatus.PROCESSING);
            uploadRepository.save(job);
            jobEventService.publish(JobEvent.from(job));

            EngineResult result = runEngine(audio, job.getLanguage());

            job.setContent(result.text());
            job.setSegmentsJson(serialize(result.segments()));
            if (job.getLanguage() == null && result.language() != null) {
                job.setLanguage(result.language());
            }
            job.setTotalChunks(1);
            job.setChunksReceived(1);
            job.setStatus(MessageStatus.COMPLETED);
            uploadRepository.save(job);
            jobEventService.publish(JobEvent.from(job));

            log.info("Job {} transcribed synchronously with {} ({} segments)",
                    jobId, result.segments().isEmpty() ? "no segments" : "engine", result.segments().size());
        } catch (Exception e) {
            log.error("Synchronous transcription failed for job {}", jobId, e);
            fail(job, "Transcription failed: " + e.getMessage());
        }
    }

    private EngineResult runEngine(Path audio, String language) throws Exception {
        if ("api".equalsIgnoreCase(mode)) {
            return apiEngine.transcribe(audio, language);
        }
        try {
            return whisperCppEngine.transcribe(audio, language);
        } catch (Exception primary) {
            if (fallbackToApi && apiEngine.isConfigured()) {
                log.warn("whisper.cpp failed ({}), falling back to API", primary.getMessage());
                return apiEngine.transcribe(audio, language);
            }
            throw primary;
        }
    }

    private void fail(Upload job, String reason) {
        job.setStatus(MessageStatus.FAILED);
        job.setFailureReason(reason);
        uploadRepository.save(job);
        jobEventService.publish(JobEvent.from(job));
    }

    private String serialize(List<TranscriptSegment> segments) {
        if (segments == null || segments.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(segments);
        } catch (Exception e) {
            log.warn("Could not serialize segments: {}", e.getMessage());
            return null;
        }
    }

    private static Path toPath(String path) {
        return path == null ? null : Path.of(path);
    }
}
