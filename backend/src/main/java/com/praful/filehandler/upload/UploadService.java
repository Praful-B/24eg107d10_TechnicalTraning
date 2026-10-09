package com.praful.filehandler.upload;

import com.praful.filehandler.user.UserRepository;
import com.praful.filehandler.user.Users;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Core service for managing transcription jobs.
 *
 * Handles: upload validation, file storage, job lifecycle, transcript assembly,
 * search, export, and deletion. All job access is scoped to the authenticated user.
 */
@Service
public class UploadService {

    // Supported audio formats (must match frontend ALLOWED_FORMATS)
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("wav", "mp3", "m4a", "flac");

    private final UploadRepository uploadRepository;
    private final ChunkResultRepository chunkResultRepository;
    private final UserRepository userRepository;
    private final JobEventService jobEventService;
    private final ChunkingService chunkingService;
    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;
    private final Path storageDir;

    public UploadService(UploadRepository uploadRepository,
                         ChunkResultRepository chunkResultRepository,
                         UserRepository userRepository,
                         JobEventService jobEventService,
                         ChunkingService chunkingService,
                         RateLimitService rateLimitService,
                         ObjectMapper objectMapper,
                         @Value("${app.storage.dir}") Path storageDir) {
        this.uploadRepository = uploadRepository;
        this.chunkResultRepository = chunkResultRepository;
        this.userRepository = userRepository;
        this.jobEventService = jobEventService;
        this.chunkingService = chunkingService;
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
        this.storageDir = storageDir;
    }

    // ------------------------------------------------------------------
    // Job lifecycle
    // ------------------------------------------------------------------

    /**
     * Validate, store, and start processing an uploaded audio file.
     * Returns immediately with a job ID; processing happens asynchronously.
     */
    public Upload createJob(MultipartFile file) throws IOException {
        requireNonEmpty(file);

        String originalName = file.getOriginalFilename();
        String extension = extensionOf(originalName);
        requireSupportedExtension(extension);
        requireValidSignature(file, extension);

        Users user = currentUser();
        rateLimitService.checkUploadRate(user.getUserId());
        rateLimitService.checkActiveJobs(user.getUserId(), uploadRepository);

        UUID jobId = UUID.randomUUID();
        Path target = storeFile(jobId, extension, file);

        Upload job = createJobEntity(jobId, user.getUserId(), originalName, target.toString());
        uploadRepository.save(job);

        // Normalize and chunk off the request thread
        chunkingService.process(jobId);
        jobEventService.publish(JobEvent.from(job));

        return job;
    }

    /** Job history for the authenticated user, newest first. */
    public List<Upload> listJobs() {
        return uploadRepository.findByUserIdOrderByCreatedAtDesc(currentUser().getUserId());
    }

    /**
     * Fetch a job, but only if it belongs to the authenticated user.
     * Returns 404 for both missing jobs and jobs belonging to other users.
     */
    public Upload getOwnedJob(UUID jobId) {
        Users user = currentUser();
        Upload job = uploadRepository.findById(jobId)
                .orElseThrow(() -> notFound("Job not found"));
        if (!user.getUserId().equals(job.getUserId())) {
            throw notFound("Job not found");
        }
        return job;
    }

    // ------------------------------------------------------------------
    // Transcript operations
    // ------------------------------------------------------------------

    public TranscriptResponse getTranscript(UUID jobId) {
        Upload job = getOwnedJob(jobId);
        return new TranscriptResponse(
                job.getJobId(),
                job.getStatus(),
                job.getLanguage(),
                job.getContent(),
                parseSegments(job.getSegmentsJson()));
    }

    /**
     * Phrase search over stored segments, returning each hit with timestamps.
     */
    public List<TranscriptMatch> search(UUID jobId, String query) {
        requireNonBlank(query, "Query must not be empty");
        Upload job = getOwnedJob(jobId);
        String needle = query.toLowerCase(Locale.ROOT);

        List<TranscriptMatch> matches = new ArrayList<>();
        for (TranscriptSegment segment : parseSegments(job.getSegmentsJson())) {
            String text = segment.text() == null ? "" : segment.text();
            if (text.toLowerCase(Locale.ROOT).contains(needle)) {
                matches.add(new TranscriptMatch(segment.start(), segment.end(), text));
            }
        }
        return matches;
    }

    /**
     * Persist manual corrections made in the transcript viewer.
     * Preserves segment timestamps; accepts either full segments or plain text.
     */
    @Transactional
    public TranscriptResponse updateTranscript(UUID jobId, UpdateTranscriptRequest request) {
        Upload job = getOwnedJob(jobId);
        if (request == null) {
            throw badRequest("Missing transcript body");
        }

        List<TranscriptSegment> segments = request.segments();
        if (segments != null && !segments.isEmpty()) {
            job.setSegmentsJson(writeSegments(segments));
            job.setContent(joinSegmentText(segments));
        } else if (request.text() != null) {
            job.setContent(request.text());
        } else {
            throw badRequest("Nothing to update");
        }

        uploadRepository.save(job);
        jobEventService.publish(JobEvent.from(job));
        return getTranscript(jobId);
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    public ResponseEntity<String> export(UUID jobId, String format) {
        Upload job = getOwnedJob(jobId);
        List<TranscriptSegment> segments = parseSegments(job.getSegmentsJson());
        String baseName = baseName(job);

        return switch (format.toLowerCase(Locale.ROOT)) {
            case "srt" -> download(baseName + ".srt", "application/x-subrip", TranscriptFormatter.toSrt(segments));
            case "vtt" -> download(baseName + ".vtt", "text/vtt", TranscriptFormatter.toVtt(segments));
            case "json" -> download(baseName + ".json", MediaType.APPLICATION_JSON_VALUE,
                    TranscriptFormatter.toJson(job, segments, objectMapper));
            default -> download(baseName + ".txt", MediaType.TEXT_PLAIN_VALUE,
                    job.getContent() == null ? "" : job.getContent());
        };
    }

    // ------------------------------------------------------------------
    // Job management
    // ------------------------------------------------------------------

    /**
     * Re-queue a failed job using the original file (kept until retention window expires).
     */
    @Transactional
    public Upload retryJob(UUID jobId) {
        Upload job = getOwnedJob(jobId);
        requireFailed(job, "Only failed jobs can be retried");
        Path original = toPath(job.getFilepath());
        requireFileExists(original, "The original audio is no longer available; upload it again");

        chunkResultRepository.deleteByJobId(jobId);
        resetJobForRetry(job);
        uploadRepository.save(job);

        chunkingService.process(jobId);
        return job;
    }

    @Transactional
    public void deleteJob(UUID jobId) {
        Upload job = getOwnedJob(jobId);
        chunkResultRepository.deleteByJobId(jobId);
        uploadRepository.delete(job);
        deleteFile(job.getFilepath());
        deleteChunkDirectory(job.getChunkDir());
    }

    // ------------------------------------------------------------------
    // Helper methods
    // ------------------------------------------------------------------

    private static void requireNonEmpty(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw badRequest("Uploaded file is empty");
        }
    }

    private void requireSupportedExtension(String extension) {
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Unsupported audio format. Allowed: " + ALLOWED_EXTENSIONS);
        }
    }

    private void requireValidSignature(MultipartFile file, String extension) throws IOException {
        try (InputStream in = file.getInputStream()) {
            if (!AudioSignatureValidator.matches(extension, in)) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "File content does not look like a valid " + extension + " audio file");
            }
        }
    }

    private Path storeFile(UUID jobId, String extension, MultipartFile file) throws IOException {
        Files.createDirectories(storageDir);
        Path target = storageDir.resolve(jobId + "." + extension);
        file.transferTo(target);
        return target;
    }

    private Upload createJobEntity(UUID jobId, UUID userId, String originalName, String filepath) {
        Upload job = new Upload();
        job.setJobId(jobId);
        job.setUserId(userId);
        job.setOriginalFileName(originalName);
        job.setFilepath(filepath);
        job.setStatus(MessageStatus.PREPARING);
        job.setTotalChunks(0);
        job.setChunksReceived(0);
        return job;
    }

    @Transactional
    private void resetJobForRetry(Upload job) {
        job.setStatus(MessageStatus.PREPARING);
        job.setChunksReceived(0);
        job.setTotalChunks(0);
        job.setContent(null);
        job.setSegmentsJson(null);
        job.setFailureReason(null);
    }

    private static String joinSegmentText(List<TranscriptSegment> segments) {
        return String.join(" ", segments.stream()
                .map(TranscriptSegment::text)
                .filter(text -> text != null && !text.isBlank())
                .map(String::trim)
                .toList());
    }

    private static void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw badRequest(message);
        }
    }

    private static void requireFailed(Upload job, String message) {
        if (job.getStatus() != MessageStatus.FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, message);
        }
    }

    private static void requireFileExists(Path path, String message) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.GONE, message);
        }
    }

    private static void deleteFile(String filepath) {
        if (filepath == null) return;
        try {
            Files.deleteIfExists(Path.of(filepath));
        } catch (IOException e) {
            // Best effort cleanup
        }
    }

    private static void deleteChunkDirectory(String chunkDir) {
        if (chunkDir == null) return;
        Path dir = Path.of(chunkDir);
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(path -> deleteFile(path.toString()));
        } catch (IOException e) {
            // Best effort
        }
    }

    private String writeSegments(List<TranscriptSegment> segments) {
        try {
            return objectMapper.writeValueAsString(segments);
        } catch (Exception e) {
            throw badRequest("Could not serialize segments");
        }
    }

    private List<TranscriptSegment> parseSegments(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<TranscriptSegment>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String baseName(Upload job) {
        String name = job.getOriginalFileName();
        if (name == null || name.isBlank()) {
            return job.getJobId().toString();
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static ResponseEntity<String> download(String filename, String contentType, String body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .body(body);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private Users currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user"));
    }

    private static String extensionOf(String originalName) {
        if (originalName == null || !originalName.contains(".")) {
            return "";
        }
        return originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private static Path toPath(String path) {
        return path == null ? null : Path.of(path);
    }
}
