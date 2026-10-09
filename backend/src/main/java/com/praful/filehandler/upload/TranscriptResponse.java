package com.praful.filehandler.upload;

import java.util.List;
import java.util.UUID;

/** Full transcript returned by GET /api/jobs/{id}/transcript. */
public record TranscriptResponse(
        UUID jobId,
        MessageStatus status,
        String language,
        String text,
        List<TranscriptSegment> segments
) {
}
