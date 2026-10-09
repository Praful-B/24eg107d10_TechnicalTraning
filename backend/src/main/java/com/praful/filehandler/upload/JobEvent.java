package com.praful.filehandler.upload;

import java.util.UUID;

/** Payload pushed to the frontend over SSE whenever a job changes state. */
public record JobEvent(
        UUID jobId,
        MessageStatus status,
        Integer chunksReceived,
        Integer totalChunks,
        String failureReason
) {
    public static JobEvent from(Upload job) {
        return new JobEvent(
                job.getJobId(),
                job.getStatus(),
                job.getChunksReceived(),
                job.getTotalChunks(),
                job.getFailureReason());
    }
}
