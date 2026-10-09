package com.praful.filehandler.upload;

import java.util.List;

/**
 * Body of PUT /api/jobs/{id}/transcript. The viewer sends the edited segments so
 * timestamps survive the correction; {@code text} alone is accepted for callers
 * that only want to replace the plain transcript.
 */
public record UpdateTranscriptRequest(String text, List<TranscriptSegment> segments) {
}
