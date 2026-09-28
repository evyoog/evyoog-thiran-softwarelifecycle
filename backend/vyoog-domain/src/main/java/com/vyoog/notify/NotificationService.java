package com.vyoog.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0355/0356/0357: an inbox item, published through the outbox in the same
 * transaction as the write that caused it (AC1 — see {@link OutboxEvent}'s own
 * caveat: a relay actually delivering these onward doesn't exist), and coalesced with
 * whatever's already open of the same kind within the digest window (AC1 of 0357 —
 * ten of the same kind of thing become one item, not ten).
 */
@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final OutboxEventRepository outbox;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;

    public NotificationService(NotificationRepository notifications, OutboxEventRepository outbox,
                                ObjectMapper json, JdbcTemplate jdbc) {
        this.notifications = notifications;
        this.outbox = outbox;
        this.json = json;
        this.jdbc = jdbc;
    }

    private int digestWindowMinutes() {
        Integer m = jdbc.queryForObject("SELECT notification_digest_window_minutes FROM app_config WHERE id = 1", Integer.class);
        return m == null ? 15 : m;
    }

    /**
     * @param kind the coalescing key alongside {@code userId} — repeated calls with
     *     the same (userId, kind) inside the digest window collapse into one row.
     */
    @Transactional
    public void notify(UUID userId, String tone, String kind, String title, String subtitle, String link) {
        if (userId == null) return; // routing to an unknown actor is a no-op, not an error

        Instant windowStart = Instant.now().minus(Duration.ofMinutes(digestWindowMinutes()));
        var existing = notifications.findFirstByUserIdAndKindAndReadAtIsNullAndCreatedAtAfterOrderByCreatedAtDesc(
            userId, kind, windowStart);

        if (existing.isPresent()) {
            Notification n = existing.get();
            n.coalesce(title, subtitle, link);
            notifications.save(n);
        } else {
            notifications.save(new Notification(userId, tone, kind, title, subtitle, link));
        }

        // VYB-0356 AC1: same transaction, same method — this either commits with the
        // notification row above or rolls back with it, never one without the other.
        outbox.save(new OutboxEvent("notification." + kind, writeOrEmpty(Map.of(
            "userId", userId.toString(), "kind", kind, "title", title))));
    }

    private String writeOrEmpty(Map<String, ?> value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    public List<Notification> inbox(UUID userId) {
        return notifications.findAllByUserIdOrderByCreatedAtDesc(userId);
    }

    public long unreadCount(UUID userId) {
        return notifications.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public void markRead(UUID id, UUID userId) {
        Notification n = notifications.findById(id).orElseThrow(NoSuchElementException::new);
        if (!n.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Not your notification");
        }
        n.markRead();
        notifications.save(n);
    }
}
