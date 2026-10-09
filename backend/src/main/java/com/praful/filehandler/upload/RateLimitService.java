package com.praful.filehandler.upload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user guards on uploads. This is deliberately in-memory: one VPS, one backend
 * instance, so a Redis round trip would buy nothing. If the service is ever scaled
 * horizontally this is the first thing that has to move to shared state.
 */
@Service
public class RateLimitService {

    private final int uploadsPerMinute;
    private final int maxActiveJobs;

    private final Map<UUID, Deque<Instant>> recentUploads = new ConcurrentHashMap<>();

    public RateLimitService(@Value("${app.rate-limit.uploads-per-minute:10}") int uploadsPerMinute,
                            @Value("${app.rate-limit.max-active-jobs:3}") int maxActiveJobs) {
        this.uploadsPerMinute = uploadsPerMinute;
        this.maxActiveJobs = maxActiveJobs;
    }

    /** Rejects the upload when the user has too many jobs in flight. */
    public void checkActiveJobs(UUID userId, UploadRepository repository) {
        if (maxActiveJobs <= 0) {
            return;
        }
        long active = repository.countByUserIdAndStatusIn(userId, Set.of(
                MessageStatus.SAVED, MessageStatus.PREPARING, MessageStatus.PROCESSING));
        if (active >= maxActiveJobs) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "You already have " + active + " jobs running (limit " + maxActiveJobs + ")");
        }
    }

    /** Sliding one-minute window; throws 429 when the user uploads too often. */
    public void checkUploadRate(UUID userId) {
        Deque<Instant> timestamps = recentUploads.computeIfAbsent(userId, key -> new ArrayDeque<>());
        Instant cutoff = Instant.now().minusSeconds(60);
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= uploadsPerMinute) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Too many uploads, try again in a minute");
            }
            timestamps.addLast(Instant.now());
        }
    }
}
