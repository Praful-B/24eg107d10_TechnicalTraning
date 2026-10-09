package com.praful.filehandler.upload;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * REST endpoints for managing transcription jobs.
 *
 * All endpoints require authentication except those explicitly noted.
 * The audio streaming endpoint supports HTTP Range requests for seeking.
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final UploadService uploadService;
    private final JobEventService jobEventService;

    public JobController(UploadService uploadService, JobEventService jobEventService) {
        this.uploadService = uploadService;
        this.jobEventService = jobEventService;
    }

    /** Upload an audio file. Returns immediately with a job id. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public JobResponse upload(@RequestParam("file") MultipartFile file) throws IOException {
        return JobResponse.from(uploadService.createJob(file));
    }

    /** Job history for the authenticated user, newest first. */
    @GetMapping
    public List<JobStatusResponse> list() {
        return uploadService.listJobs().stream()
                .map(JobStatusResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public JobStatusResponse get(@PathVariable UUID id) {
        return JobStatusResponse.from(uploadService.getOwnedJob(id));
    }

    @GetMapping("/{id}/transcript")
    public TranscriptResponse transcript(@PathVariable UUID id) {
        return uploadService.getTranscript(id);
    }

    /**
     * Correct transcription mistakes without losing segment timestamps.
     * Accepts either full segment list or plain text.
     */
    @PutMapping("/{id}/transcript")
    public TranscriptResponse updateTranscript(
            @PathVariable UUID id,
            @RequestBody UpdateTranscriptRequest request) {
        return uploadService.updateTranscript(id, request);
    }

    /**
     * Phrase search inside a transcript; each hit carries its timestamps.
     */
    @GetMapping("/{id}/search")
    public List<TranscriptMatch> search(
            @PathVariable UUID id,
            @RequestParam("q") String query) {
        return uploadService.search(id, query);
    }

    /**
     * Live status updates via Server-Sent Events.
     * The token can be passed as ?token= since EventSource cannot set headers.
     */
    @GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID id) {
        Upload job = uploadService.getOwnedJob(id);
        return jobEventService.subscribe(JobEvent.from(job));
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<String> export(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "txt") String format) {
        return uploadService.export(id, format);
    }

    /**
     * Stream the original audio file with Range support for seeking.
     * The token travels as a query parameter since <audio> tags cannot set headers.
     */
    @GetMapping("/{id}/audio")
    public void streamAudio(
            @PathVariable UUID id,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        Upload job = uploadService.getOwnedJob(id);
        streamFile(job.getFilepath(), job.getOriginalFileName(), request, response);
    }

    /** Re-run a failed job from its still-stored original file. */
    @PostMapping("/{id}/retry")
    public JobStatusResponse retry(@PathVariable UUID id) {
        return JobStatusResponse.from(uploadService.retryJob(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        uploadService.deleteJob(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // File streaming helpers
    // ------------------------------------------------------------------

    private static void streamFile(String filepath, String originalName,
                                   HttpServletRequest request,
                                   HttpServletResponse response) throws IOException {
        Path path = filepath == null ? null : Path.of(filepath);
        if (path == null || !Files.isRegularFile(path)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Audio file is no longer available");
            return;
        }

        long length = Files.size(path);
        Range range = parseRange(request.getHeader("Range"), length);

        response.setHeader("Accept-Ranges", "bytes");
        response.setContentType(mimeTypeOf(originalName));

        if (range.isPartial) {
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader("Content-Range",
                    "bytes " + range.start + "-" + range.end + "/" + length);
        }

        response.setContentLengthLong(range.length);
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            file.seek(range.start);
            try (OutputStream out = response.getOutputStream()) {
                byte[] buffer = new byte[64 * 1024];
                long remaining = range.length;
                while (remaining > 0) {
                    int read = file.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) break;
                    out.write(buffer, 0, read);
                    remaining -= read;
                }
            }
        }
    }

    private record Range(long start, long end, long length, boolean isPartial) {
        private static Range full(long length) {
            return new Range(0, length - 1, length, false);
        }

        private static Range partial(long start, long end, long length) {
            return new Range(start, end, end - start + 1, true);
        }
    }

    private static Range parseRange(String header, long length) {
        if (header == null || !header.startsWith("bytes=")) {
            return Range.full(length);
        }
        try {
            String value = header.substring("bytes=".length()).split(",")[0].trim();
            String[] parts = value.split("-", -1);
            long start = parts[0].isEmpty() ? 0 : Long.parseLong(parts[0]);
            long end = parts.length < 2 || parts[1].isEmpty() ? length - 1 : Long.parseLong(parts[1]);

            if (start < 0 || end >= length || start > end) {
                return Range.full(length); // Invalid range, serve full file
            }
            return Range.partial(start, Math.min(end, length - 1), length);
        } catch (NumberFormatException e) {
            return Range.full(length);
        }
    }

    private static String mimeTypeOf(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return switch (name) {
            case String s when s.endsWith(".mp3") -> "audio/mpeg";
            case String s when s.endsWith(".m4a") -> "audio/mp4";
            case String s when s.endsWith(".flac") -> "audio/flac";
            default -> "audio/wav";
        };
    }
}
