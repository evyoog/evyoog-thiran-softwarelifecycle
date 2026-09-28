package com.vyoog.notify;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * VYB-0356/0791: the outbox row a notification is published through — written in the
 * same transaction as the {@code notification} row itself (both are plain JPA saves
 * inside {@link NotificationService#notify}'s single {@code @Transactional} method),
 * so "no notification exists without its outbox entry" holds by construction: either
 * both commit or neither does. {@link com.vyoog.notify.NotificationRelayService} is
 * the real relay reading these onward — a periodic poll pushing to any live SSE
 * connection, marking {@code publishedAt} either way.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {}

    public OutboxEvent(String eventType, String payload) {
        this.eventType = eventType;
        this.payload = payload;
    }

    /** VYB-0791: the relay's own mark that it attempted delivery — not "someone was listening"; an offline user simply sees this in their inbox on next load instead. */
    public void markPublished() { this.publishedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getPublishedAt() { return publishedAt; }
}
