package com.praful.filehandler.common;

import java.time.LocalDateTime;

/** Consistent error body returned by every failing request. */
public record ApiError(
        LocalDateTime timestamp,
        int status,
        String error,
        String message,
        String path
) {
}
