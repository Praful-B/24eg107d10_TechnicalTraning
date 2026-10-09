package com.praful.filehandler.rabbitmq;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.praful.filehandler.upload.TranscriptSegment;

import java.util.List;
import java.util.UUID;

/**
 * Result published by the Python worker to the post-processing queue.
 * Unknown fields (e.g. the worker's debug traceback) are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TranscriptionResultDto(
        UUID jobId,
        Integer chunkIndex,
        Integer totalChunks,
        Double startOffset,
        Double endOffset,
        String language,
        Double duration,
        List<TranscriptSegment> segments,
        String text,
        Boolean success,
        String error
) {
}
