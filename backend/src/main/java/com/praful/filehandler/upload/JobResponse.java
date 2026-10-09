package com.praful.filehandler.upload;

import java.util.UUID;

/** Response returned by POST /api/jobs so the client can start tracking the job. */
public record JobResponse(UUID jobId, MessageStatus status) {
    public static JobResponse from(Upload job) {
        return new JobResponse(job.getJobId(), job.getStatus());
    }
}
