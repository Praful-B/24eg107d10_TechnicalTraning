package com.praful.filehandler.upload;

/**
 * A single timestamped transcript segment. Times are global (seconds from the
 * start of the audio) so the frontend can seek the player directly.
 */
public record TranscriptSegment(Double start, Double end, String text) {
}
