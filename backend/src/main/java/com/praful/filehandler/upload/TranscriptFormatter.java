package com.praful.filehandler.upload;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** Converts a stored transcript into the export formats offered by the API. */
public final class TranscriptFormatter {

    private TranscriptFormatter() {
    }

    public static String toSrt(List<TranscriptSegment> segments) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (TranscriptSegment segment : segments) {
            sb.append(index++).append('\n')
                    .append(time(segment.start(), ',')).append(" --> ").append(time(segment.end(), ',')).append('\n')
                    .append(clean(segment.text())).append("\n\n");
        }
        return sb.toString();
    }

    public static String toVtt(List<TranscriptSegment> segments) {
        StringBuilder sb = new StringBuilder("WEBVTT\n\n");
        for (TranscriptSegment segment : segments) {
            sb.append(time(segment.start(), '.')).append(" --> ").append(time(segment.end(), '.')).append('\n')
                    .append(clean(segment.text())).append("\n\n");
        }
        return sb.toString();
    }

    public static String toJson(Upload job, List<TranscriptSegment> segments, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "jobId", job.getJobId().toString(),
                    "language", job.getLanguage() == null ? "" : job.getLanguage(),
                    "text", job.getContent() == null ? "" : job.getContent(),
                    "segments", segments));
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String clean(String text) {
        return text == null ? "" : text.trim();
    }

    private static String time(Double seconds, char millisSeparator) {
        double value = (seconds == null || seconds < 0) ? 0 : seconds;
        long totalMillis = Math.round(value * 1000);
        long hours = totalMillis / 3_600_000;
        long minutes = (totalMillis % 3_600_000) / 60_000;
        long secs = (totalMillis % 60_000) / 1000;
        long millis = totalMillis % 1000;
        return String.format("%02d:%02d:%02d%c%03d", hours, minutes, secs, millisSeparator, millis);
    }
}
