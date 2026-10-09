package com.praful.filehandler.upload;

import java.time.LocalDateTime;
import java.util.UUID;

/** Job status and progress used by GET /api/jobs and GET /api/jobs/{id}. */
public record JobStatusResponse(
        UUID jobId,
        MessageStatus status,
        String originalFileName,
        Integer totalChunks,
        Integer chunksReceived,
        String language,
        String failureReason,
        boolean hasTranscript,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static JobStatusResponse from(Upload job) {
        return new JobStatusResponse(
                job.getJobId(),
                job.getStatus(),
                job.getOriginalFileName(),
                job.getTotalChunks(),
                job.getChunksReceived(),
                job.getLanguage(),
                job.getFailureReason(),
                job.getContent() != null && !job.getContent().isBlank(),
                job.getCreatedAt(),
                job.getUpdatedAt());
    }
}
