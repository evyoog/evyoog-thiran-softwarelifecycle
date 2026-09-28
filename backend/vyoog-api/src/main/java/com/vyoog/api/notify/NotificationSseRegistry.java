package com.vyoog.api.notify;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * VYB-0791: live SSE connections, keyed by user id — a user can have more than one
 * open (multiple tabs/devices), so each entry is a list, not a single emitter. In
 * memory, per instance — the same single-instance-deployment caveat {@code
 * RateLimiter}/{@code DetectionSweepService}'s own concurrency guard already
 * disclose: a second app instance would need its own connections rebuilt, or a real
 * pub/sub layer (Redis, etc.) neither exists nor is needed at this deployment's scale.
 * Lives in {@code vyoog-api}, not {@code vyoog-domain} — {@code SseEmitter} is a
 * {@code spring-webmvc} type, and domain must not depend on web (ArchUnit).
 */
@Component
public class NotificationSseRegistry {

    private static final Logger log = LoggerFactory.getLogger(NotificationSseRegistry.class);

    private final Map<UUID, List<SseEmitter>> byUser = new ConcurrentHashMap<>();

    public void register(UUID userId, SseEmitter emitter) {
        List<SseEmitter> emitters = byUser.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
    }

    /** @return true if at least one live connection actually received this — not a delivery guarantee, just what the relay logs. */
    public boolean push(UUID userId, String eventName, Map<String, Object> data) {
        List<SseEmitter> emitters = byUser.get(userId);
        if (emitters == null || emitters.isEmpty()) return false;
        boolean sentToAny = false;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
                sentToAny = true;
            } catch (Exception e) {
                log.debug("[notify-sse] dropping a dead connection for user {}: {}", userId, e.getMessage());
                emitters.remove(emitter);
            }
        }
        return sentToAny;
    }
}
