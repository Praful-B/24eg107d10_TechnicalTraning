package com.praful.filehandler.transcription;

import java.nio.file.Path;

/**
 * A synchronous transcription backend. Implementations must return global
 * (absolute) segment timestamps in seconds so the frontend player can seek.
 */
public interface TranscriptionEngine {

    /** Short, human-readable name used in logs and failure messages. */
    String name();

    /**
     * Transcribe an entire audio file. Each engine handles its own preprocessing
     * (e.g. whisper.cpp needs a 16 kHz mono WAV, a hosted API accepts the original).
     *
     * @param audio    the uploaded audio file
     * @param language optional ISO language hint; {@code null}/blank means auto-detect
     */
    EngineResult transcribe(Path audio, String language) throws Exception;
}
