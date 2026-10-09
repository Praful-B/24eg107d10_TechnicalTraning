package com.praful.filehandler.upload;

import java.io.IOException;
import java.io.InputStream;

/**
 * Sniffs the first bytes of an upload to confirm it really is the audio format the
 * extension claims. Extensions are trivially spoofed, file signatures are not.
 */
public final class AudioSignatureValidator {

    private AudioSignatureValidator() {
    }

    private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] WAVE = {'W', 'A', 'V', 'E'};
    private static final byte[] FLAC = {'f', 'L', 'a', 'C'};
    private static final byte[] ID3 = {'I', 'D', '3'};
    private static final byte[] FTYP = {'f', 't', 'y', 'p'};

    /**
     * @return true when the leading bytes look like the container for the given extension.
     */
    public static boolean matches(String extension, InputStream in) {
        byte[] header = new byte[12];
        int read;
        try {
            read = in.readNBytes(header, 0, header.length);
        } catch (IOException e) {
            return false;
        }
        if (read < 4) {
            return false;
        }
        return switch (extension == null ? "" : extension) {
            case "wav" -> startsWith(header, RIFF, 0) && startsWith(header, WAVE, 8);
            case "flac" -> startsWith(header, FLAC, 0);
            case "mp3" -> startsWith(header, ID3, 0) || looksLikeMp3Frame(header);
            // m4a is an ISO-BMFF container: "....ftyp<brand>"
            case "m4a" -> startsWith(header, FTYP, 4);
            default -> false;
        };
    }

    private static boolean looksLikeMp3Frame(byte[] header) {
        return (header[0] & 0xFF) == 0xFF && (header[1] & 0xE0) == 0xE0;
    }

    private static boolean startsWith(byte[] source, byte[] prefix, int offset) {
        if (source.length < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (source[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
