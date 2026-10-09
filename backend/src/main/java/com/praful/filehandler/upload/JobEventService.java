package com.praful.filehandler.upload;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks one SSE subscriber per job and pushes status events to it.
 * For an MVP a single subscriber per job is enough.
 */
@Service
public class JobEventService {

    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(JobEvent initial) {
        UUID jobId = initial.jobId();
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.put(jobId, emitter);
        emitter.onCompletion(() -> emitters.remove(jobId));
        emitter.onTimeout(() -> emitters.remove(jobId));
        emitter.onError(e -> emitters.remove(jobId));

        try {
            emitter.send(SseEmitter.event().name("status").data(initial));
        } catch (IOException e) {
            emitters.remove(jobId);
        }
        return emitter;
    }

    public void publish(JobEvent event) {
        SseEmitter emitter = emitters.get(event.jobId());
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("status").data(event));
        } catch (IOException e) {
            emitters.remove(event.jobId());
        }
    }
}
