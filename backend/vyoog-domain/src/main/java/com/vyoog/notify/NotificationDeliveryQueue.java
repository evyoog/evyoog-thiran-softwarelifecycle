package com.vyoog.notify;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0955 (D47): the queue email and chat go out through. A row is written in the same transaction as the change that caused it
 * (so a rolled-back change sends nothing), and a scheduled job sends it afterwards; sending can never fail or slow the change itself.
 *
 * <p>Email: an urgent kind is due at once, except that a second one of the same kind to the same person inside the digest window waits
 * for the digest instead (the first one has already told them; the inbox row says how many). Everything else is due one digest window
 * after it was queued, and when any of a person's non-urgent email is due all of it goes in one message. Nothing is dropped.
 * Chat: rows with the same {@code dedupe_key} are one post.
 */
@Service
public class NotificationDeliveryQueue {

    /** After this many failed attempts a row is left failed and shown to the administrator, not retried for ever. */
    public static final int MAX_ATTEMPTS = 5;

    private final JdbcTemplate jdbc;

    public NotificationDeliveryQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Delivery(UUID id, NotificationChannel channel, String kind, UUID userId, String email, String displayName,
                           String title, String subtitle, String link, boolean urgent, int attempts, Instant createdAt) {}

    public record Stats(int pending, int sentLast24h, int failedLast24h, String lastError) {}

    private int digestWindowMinutes() {
        Integer m = jdbc.queryForObject("SELECT notification_digest_window_minutes FROM app_config WHERE id = 1", Integer.class);
        return m == null ? 15 : m;
    }

    public void enqueueEmail(UUID userId, UUID notificationId, String kind, String title, String subtitle, String link, boolean urgent) {
        int window = digestWindowMinutes();
        boolean goNow = urgent;
        if (urgent) {
            Boolean recent = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM notification_delivery
                                WHERE channel = 'EMAIL' AND user_id = ? AND kind = ? AND urgent AND state IN ('PENDING', 'SENT')
                                  AND created_at > now() - make_interval(mins => ?))
                """, Boolean.class, userId, kind, window);
            goNow = !Boolean.TRUE.equals(recent);
        }
        jdbc.update("""
            INSERT INTO notification_delivery (channel, kind, user_id, notification_id, title, subtitle, link, urgent, due_at)
            VALUES ('EMAIL', ?, ?, ?, ?, ?, ?, ?, now() + CASE WHEN ? THEN interval '0' ELSE make_interval(mins => ?) END)
            """, kind, userId, notificationId, title, subtitle, link, goNow, goNow, window);
    }

    /** @return whether a post was queued (false when the same key was already queued or sent) */
    public boolean enqueueChat(String kind, String title, String subtitle, String link, String dedupeKey) {
        return jdbc.update("""
            INSERT INTO notification_delivery (channel, kind, title, subtitle, link, urgent, dedupe_key)
            VALUES ('CHAT', ?, ?, ?, ?, false, ?) ON CONFLICT DO NOTHING
            """, kind, title, subtitle, link, dedupeKey) > 0;
    }

    // ---------------------------------------------------------------- what the sender reads and records

    private static final String SELECT = """
        SELECT d.id, d.channel, d.kind, d.user_id, u.email, u.display_name, d.title, d.subtitle, d.link, d.urgent, d.attempts, d.created_at
          FROM notification_delivery d LEFT JOIN app_user u ON u.id = d.user_id
        """;

    private static Delivery map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Delivery(rs.getObject("id", UUID.class), NotificationChannel.valueOf(rs.getString("channel")), rs.getString("kind"),
            rs.getObject("user_id", UUID.class), rs.getString("email"), rs.getString("display_name"), rs.getString("title"),
            rs.getString("subtitle"), rs.getString("link"), rs.getBoolean("urgent"), rs.getInt("attempts"),
            rs.getTimestamp("created_at").toInstant());
    }

    public List<Delivery> dueUrgentEmail(int limit) {
        return jdbc.query(SELECT + " WHERE d.channel = 'EMAIL' AND d.state = 'PENDING' AND d.urgent AND d.due_at <= now() ORDER BY d.created_at LIMIT ?",
            (rs, n) -> map(rs), limit);
    }

    /** The people with at least one non-urgent email that is due; their whole pending digest is read with {@link #digestOf}. */
    public List<UUID> usersWithDueDigest(int limit) {
        return jdbc.queryForList("""
            SELECT user_id FROM notification_delivery
             WHERE channel = 'EMAIL' AND state = 'PENDING' AND NOT urgent AND due_at <= now()
             GROUP BY user_id ORDER BY min(created_at) LIMIT ?
            """, UUID.class, limit);
    }

    public List<Delivery> digestOf(UUID userId) {
        return jdbc.query(SELECT + " WHERE d.channel = 'EMAIL' AND d.state = 'PENDING' AND NOT d.urgent AND d.user_id = ? ORDER BY d.created_at",
            (rs, n) -> map(rs), userId);
    }

    public List<Delivery> dueChat(int limit) {
        return jdbc.query(SELECT + " WHERE d.channel = 'CHAT' AND d.state = 'PENDING' AND d.due_at <= now() ORDER BY d.created_at LIMIT ?",
            (rs, n) -> map(rs), limit);
    }

    public void markSent(List<UUID> ids) {
        for (UUID id : ids) {
            jdbc.update("UPDATE notification_delivery SET state = 'SENT', sent_at = now(), last_error = NULL WHERE id = ?", id);
        }
    }

    /** Records a failed attempt: tried again after 1, 2, 4 and 8 minutes, then left failed. */
    public void markFailed(List<UUID> ids, String error) {
        String clean = error == null ? "failed" : (error.length() > 500 ? error.substring(0, 500) : error);
        for (UUID id : ids) {
            jdbc.update("""
                UPDATE notification_delivery
                   SET attempts = attempts + 1, last_error = ?,
                       state = CASE WHEN attempts + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END,
                       due_at = now() + make_interval(mins => power(2, attempts)::int)
                 WHERE id = ?
                """, clean, MAX_ATTEMPTS, id);
        }
    }

    /** What is still waiting when a channel is not set up: given up on after a day, so a switched-off channel does not fill up. */
    public int failStale(NotificationChannel channel, String reason) {
        return jdbc.update("""
            UPDATE notification_delivery SET state = 'FAILED', last_error = ?
             WHERE channel = ? AND state = 'PENDING' AND created_at < now() - interval '1 day'
            """, reason, channel.name());
    }

    /** Sent and failed rows older than this are removed by the nightly purge. */
    public int purgeOlderThan(Instant cutoff) {
        return jdbc.update("DELETE FROM notification_delivery WHERE state <> 'PENDING' AND created_at < ?", Timestamp.from(cutoff));
    }

    public Stats stats(NotificationChannel channel) {
        Integer pending = jdbc.queryForObject("SELECT count(*) FROM notification_delivery WHERE channel = ? AND state = 'PENDING'", Integer.class, channel.name());
        Integer sent = jdbc.queryForObject("SELECT count(*) FROM notification_delivery WHERE channel = ? AND state = 'SENT' AND sent_at > now() - interval '24 hours'", Integer.class, channel.name());
        Integer failed = jdbc.queryForObject("SELECT count(*) FROM notification_delivery WHERE channel = ? AND state = 'FAILED' AND created_at > now() - interval '24 hours'", Integer.class, channel.name());
        List<String> err = jdbc.queryForList("SELECT last_error FROM notification_delivery WHERE channel = ? AND last_error IS NOT NULL ORDER BY created_at DESC LIMIT 1", String.class, channel.name());
        return new Stats(pending == null ? 0 : pending, sent == null ? 0 : sent, failed == null ? 0 : failed, err.isEmpty() ? null : err.get(0));
    }
}
