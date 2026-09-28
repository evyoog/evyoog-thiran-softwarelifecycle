package com.vyoog.api.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.notify.OutboxEvent;
import com.vyoog.notify.OutboxEventRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0791: closes VYB-0356's real remaining gap — the outbox row was already
 * written transactionally (session 13), but nothing ever read it back out. This is
 * that relay: poll unpublished {@code outbox_event} rows, push each
 * {@code notification.*} one to any live SSE connection for its user via {@link
 * NotificationSseRegistry}, and mark it published either way — an offline user
 * simply sees it in their inbox on next load, same as before this existed, rather
 * than the poll looping on it forever.
 */
@Service
public class NotificationRelayService {

    private static final Logger log = LoggerFactory.getLogger(NotificationRelayService.class);
    private static final int BATCH_SIZE = 50;

    private final OutboxEventRepository outbox;
    private final NotificationSseRegistry registry;
    private final ObjectMapper json;

    public NotificationRelayService(OutboxEventRepository outbox, NotificationSseRegistry registry, ObjectMapper json) {
        this.outbox = outbox;
        this.registry = registry;
        this.json = json;
    }

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void relay() {
        List<OutboxEvent> pending = outbox.findAllByPublishedAtIsNullOrderByIdAsc(PageRequest.of(0, BATCH_SIZE));
        for (OutboxEvent event : pending) {
            if (event.getEventType().startsWith("notification.")) {
                relayOne(event);
            }
            // Non-notification event types are marked published too — this relay is
            // the only consumer of outbox_event that exists; a future second consumer
            // (email, etc.) would need its own published/consumed tracking, not this one.
            event.markPublished();
            outbox.save(event);
        }
    }

    private void relayOne(OutboxEvent event) {
        try {
            JsonNode payload = json.readTree(event.getPayload());
            String userIdText = payload.path("userId").asText(null);
            if (userIdText == null) return;
            UUID userId = UUID.fromString(userIdText);
            boolean delivered = registry.push(userId, "notification", Map.of(
                "kind", payload.path("kind").asText(""),
                "title", payload.path("title").asText("")));
            if (!delivered) {
                log.debug("[notify-relay] no live connection for user {} — will surface on next inbox load instead", userId);
            }
        } catch (Exception e) {
            // A malformed payload shouldn't wedge the whole relay loop on this one row forever.
            log.warn("[notify-relay] could not relay outbox event {}: {}", event.getId(), e.getMessage());
        }
    }
}
