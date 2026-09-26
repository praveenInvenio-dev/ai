package com.aistorystudio.service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory SSE emitter registry so the Production Dashboard gets live progress. */
@Service
public class JobEventService {

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID jobId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout; client disconnects when done
        emitters.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(jobId, emitter));
        emitter.onTimeout(() -> remove(jobId, emitter));
        emitter.onError(e -> remove(jobId, emitter));
        return emitter;
    }

    public void publish(UUID jobId, Object payload) {
        List<SseEmitter> list = emitters.get(jobId);
        if (list == null) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(payload));
            } catch (IOException e) {
                remove(jobId, emitter);
            }
        }
    }

    public void complete(UUID jobId) {
        List<SseEmitter> list = emitters.get(jobId);
        if (list == null) return;
        for (SseEmitter emitter : list) {
            emitter.complete();
        }
        emitters.remove(jobId);
    }

    private void remove(UUID jobId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(jobId);
        if (list != null) list.remove(emitter);
    }
}
