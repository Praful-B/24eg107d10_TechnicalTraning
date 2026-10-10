package com.praful.filehandler.transcription;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Converts arbitrary input audio into the 16 kHz mono PCM WAV that Whisper expects.
 * Shared by the chunking path and the embedded whisper.cpp path.
 */
@Component
public class FfmpegAudioNormalizer {

    private final String ffmpegPath;

    public FfmpegAudioNormalizer(@Value("${app.ffmpeg.path:ffmpeg}") String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    /** Normalize {@code input} into {@code outputDir/normalized.wav} and return it. */
    public Path toWhisperWav(Path input, Path outputDir) throws IOException, InterruptedException {
        Files.createDirectories(outputDir);
        Path output = outputDir.resolve("normalized.wav");
        run(List.of(
                ffmpegPath, "-hide_banner", "-loglevel", "error", "-y",
                "-i", input.toString(),
                "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le",
                output.toString()));
        return output;
    }

    private void run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException(command.get(0) + " failed (exit " + exitCode + "): " + lastLine(output));
        }
    }

    private static String lastLine(String output) {
        String[] lines = output.split("\\R");
        return lines.length == 0 ? "" : lines[lines.length - 1];
    }
}
