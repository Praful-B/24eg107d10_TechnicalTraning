package com.praful.filehandler.rabbitmq;

import com.praful.filehandler.upload.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Consumes transcription results from the Python worker and assembles the final transcript.
 *
 * <p>Each chunk result is stored idempotently (duplicates are ignored). When all chunks
 * have arrived, the transcript is assembled in chunk order with overlap trimming.
 *
 * <p>The job row is fetched with a write lock to ensure thread-safe assembly.
 */
@Component
public class MessageProcesser {

    private static final Logger log = LoggerFactory.getLogger(MessageProcesser.class);

    /** Segments starting this much before the cursor are overlap and get trimmed. */
    private static final double OVERLAP_EPSILON = 0.05;

    private final UploadRepository uploadRepository;
    private final ChunkResultRepository chunkResultRepository;
    private final JobEventService jobEventService;
    private final ObjectMapper objectMapper;

    public MessageProcesser(UploadRepository uploadRepository,
                            ChunkResultRepository chunkResultRepository,
                            JobEventService jobEventService,
                            ObjectMapper objectMapper) {
        this.uploadRepository = uploadRepository;
        this.chunkResultRepository = chunkResultRepository;
        this.jobEventService = jobEventService;
        this.objectMapper = objectMapper;
    }

    /**
     * Process a transcription result from the worker.
     * Handles: failure results, duplicate chunks, and transcript assembly.
     */
    @Transactional
    public void processMessage(TranscriptionResultDto result) {
        if (result == null || result.jobId() == null) {
            log.warn("Received result without jobId, ignoring");
            return;
        }

        Upload job = findJobWithLock(result.jobId());
        if (job == null) {
            log.warn("Result for unknown job {}", result.jobId());
            return;
        }

        // Already complete? Ignore late-arriving results
        if (job.getStatus() == MessageStatus.COMPLETED) {
            log.info("Job {} already completed, ignoring late result", result.jobId());
            return;
        }

        // Handle failure
        if (Boolean.FALSE.equals(result.success())) {
            handleFailure(job, result);
            return;
        }

        // Handle successful chunk
        handleSuccess(job, result);
    }

    private Upload findJobWithLock(java.util.UUID jobId) {
        return uploadRepository.findWithLockingByJobId(jobId).orElse(null);
    }

    private void handleFailure(Upload job, TranscriptionResultDto result) {
        job.setStatus(MessageStatus.FAILED);
        job.setFailureReason(result.error() == null ? "Transcription failed" : result.error());
        uploadRepository.save(job);
        jobEventService.publish(JobEvent.from(job));
        log.error("Job {} FAILED: {}", job.getJobId(), job.getFailureReason());
    }

    private void handleSuccess(Upload job, TranscriptionResultDto result) {
        int chunkIndex = result.chunkIndex() == null ? 0 : result.chunkIndex();

        // Idempotency: skip if we already have this chunk
        if (chunkResultRepository.findByJobIdAndChunkIndex(job.getJobId(), chunkIndex).isPresent()) {
            log.info("Duplicate chunk {} for job {}, skipping", chunkIndex, job.getJobId());
            return;
        }

        // Save chunk result
        ChunkResult chunk = createChunkResult(job, result);
        chunkResultRepository.save(chunk);

        // Update progress
        int received = incrementChunksReceived(job);
        int total = updateTotalChunks(job, result.totalChunks());
        updateLanguage(job, result.language());
        job.setStatus(MessageStatus.PROCESSING);
        uploadRepository.save(job);

        log.info("Job {} progress: {}/{} chunks", job.getJobId(), received, total);

        // Check if we can assemble
        if (received >= total) {
            assembleTranscript(job);
        }
        jobEventService.publish(JobEvent.from(job));
    }

    private ChunkResult createChunkResult(Upload job, TranscriptionResultDto result) {
        ChunkResult chunk = new ChunkResult();
        chunk.setJobId(job.getJobId());
        chunk.setChunkIndex(result.chunkIndex());
        chunk.setTotalChunks(result.totalChunks());
        chunk.setStartOffset(result.startOffset());
        chunk.setEndOffset(result.endOffset());
        chunk.setText(result.text());
        chunk.setLanguage(result.language());
        chunk.setSegmentsJson(serializeSegments(result.segments()));
        chunk.setSuccess(true);
        return chunk;
    }

    private int incrementChunksReceived(Upload job) {
        int current = job.getChunksReceived() == null ? 0 : job.getChunksReceived();
        job.setChunksReceived(current + 1);
        return current + 1;
    }

    private int updateTotalChunks(Upload job, Integer newTotal) {
        int current = job.getTotalChunks() == null ? 0 : job.getTotalChunks();
        int max = Math.max(current, newTotal == null ? 1 : newTotal);
        job.setTotalChunks(max);
        return max;
    }

    private void updateLanguage(Upload job, String language) {
        if (job.getLanguage() == null && language != null) {
            job.setLanguage(language);
        }
    }

    /**
     * Assemble the final transcript from all chunk results.
     *
     * <p>Chunks overlap slightly. Segments entirely in the overlap region are dropped
     * (they're duplicate audio). Segments that start in the overlap but extend beyond
     * it are kept with their start time adjusted to the cursor.
     */
    private void assembleTranscript(Upload job) {
        List<ChunkResult> chunks = chunkResultRepository.findByJobId(job.getJobId());
        chunks.sort(Comparator.comparingInt(this::safeChunkIndex));

        List<TranscriptSegment> segments = new ArrayList<>();
        double cursor = 0;

        for (ChunkResult chunk : chunks) {
            double baseOffset = chunk.getStartOffset() == null ? 0 : chunk.getStartOffset();
            List<TranscriptSegment> chunkSegments = deserializeSegments(chunk.getSegmentsJson());

            if (chunkSegments.isEmpty()) {
                // Fallback to plain text if no segments
                addPlainTextSegment(chunk, baseOffset, segments, cursor);
                continue;
            }

            for (TranscriptSegment segment : chunkSegments) {
                double start = segment.start() == null ? baseOffset : segment.start();
                double end = segment.end() == null ? start : segment.end();

                if (isInOverlap(end, cursor)) {
                    continue; // Drop duplicate
                }

                TranscriptSegment adjusted = adjustForOverlap(start, end, segment.text(), cursor);
                segments.add(adjusted);
                cursor = Math.max(cursor, end);
            }
        }

        String assembledText = joinSegmentText(segments);
        job.setContent(assembledText);
        job.setSegmentsJson(serializeSegments(segments));
        job.setStatus(MessageStatus.COMPLETED);
        uploadRepository.save(job);

        log.info("Job {} completed with {} segments", job.getJobId(), segments.size());
    }

    private int safeChunkIndex(ChunkResult chunk) {
        return chunk.getChunkIndex() == null ? Integer.MAX_VALUE : chunk.getChunkIndex();
    }

    private boolean isInOverlap(double end, double cursor) {
        // A segment is in the overlap region if it ends at or before the cursor
        // (accounting for small floating point differences)
        return end <= cursor + OVERLAP_EPSILON;
    }

    private TranscriptSegment adjustForOverlap(double start, double end, String text, double cursor) {
        // If segment starts before cursor, it's partially in overlap - adjust start to cursor
        if (start < cursor - OVERLAP_EPSILON) {
            return new TranscriptSegment(cursor, end, text);
        }
        // Segment starts at or after cursor - keep as is
        return new TranscriptSegment(start, end, text);
    }

    private void addPlainTextSegment(ChunkResult chunk, double baseOffset,
                                     List<TranscriptSegment> segments, double cursor) {
        String text = chunk.getText();
        if (text == null || text.isBlank()) return;

        double end = chunk.getEndOffset() == null ? baseOffset : chunk.getEndOffset();
        segments.add(new TranscriptSegment(baseOffset, end, text.trim()));
        cursor = Math.max(cursor, end);
    }

    private String joinSegmentText(List<TranscriptSegment> segments) {
        return String.join(" ", segments.stream()
                .map(TranscriptSegment::text)
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .toList());
    }

    // ------------------------------------------------------------------
    // Serialization helpers
    // ------------------------------------------------------------------

    private String serializeSegments(List<TranscriptSegment> segments) {
        if (segments == null || segments.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(segments);
        } catch (Exception e) {
            log.warn("Failed to serialize segments: {}", e.getMessage());
            return null;
        }
    }

    private List<TranscriptSegment> deserializeSegments(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<TranscriptSegment>>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize segments, returning empty list");
            return List.of();
        }
    }
}
