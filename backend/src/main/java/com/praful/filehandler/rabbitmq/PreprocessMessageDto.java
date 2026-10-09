package com.praful.filehandler.rabbitmq;

import java.util.UUID;

/**
 * One work item for the Python worker. A long recording is split into several
 * chunks, each published as its own message so workers can transcribe in parallel.
 * {@code attempt} is incremented on every retry so the worker can stop after a limit.
 */
public record PreprocessMessageDto(
        UUID jobId,
        String filePath,
        Status status,
        Integer chunkIndex,
        Integer totalChunks,
        Double startOffset,
        Double endOffset,
        Double duration,
        String language,
        Integer attempt
) {
    public PreprocessMessageDto withAttempt(int nextAttempt) {
        return new PreprocessMessageDto(jobId, filePath, status, chunkIndex, totalChunks,
                startOffset, endOffset, duration, language, nextAttempt);
    }
}
