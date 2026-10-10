package com.praful.filehandler.transcription;

import com.praful.filehandler.upload.TranscriptSegment;

import java.util.List;

/**
 * Normalized output shared by every transcription engine, so the rest of the app
 * does not care whether the text came from whisper.cpp or a hosted API.
 */
public record EngineResult(List<TranscriptSegment> segments, String text, String language) {
}
