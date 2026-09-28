package com.vyoog.notify;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * VYB-0355: one inbox item for one user, about one event. VYB-0357: {@code kind}/
 * {@code occurrenceCount} let repeats within a window collapse into this same row
 * instead of each becoming its own item — see {@link NotificationService#notify}.
 */
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String tone = "info";

    @Column(nullable = false)
    private String title;

    private String subtitle;

    private String link;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** VYB-0357: the class of event this is — the digest's own coalescing key together with {@code userId}. */
    private String kind;

    @Column(name = "occurrence_count", nullable = false)
    private int occurrenceCount = 1;

    protected Notification() {}

    public Notification(UUID userId, String tone, String kind, String title, String subtitle, String link) {
        this.userId = userId;
        this.tone = tone;
        this.kind = kind;
        this.title = title;
        this.subtitle = subtitle;
        this.link = link;
    }

    public void markRead() { this.readAt = Instant.now(); }

    /** VYB-0357 AC1: ten edits within the window produce one item — this is that collapse, called instead of a second insert. */
    public void coalesce(String latestTitle, String latestSubtitle, String latestLink) {
        this.occurrenceCount++;
        this.title = latestTitle;
        this.subtitle = latestSubtitle;
        this.link = latestLink;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTone() { return tone; }
    public String getTitle() { return title; }
    public String getSubtitle() { return subtitle; }
    public String getLink() { return link; }
    public Instant getReadAt() { return readAt; }
    public Instant getCreatedAt() { return createdAt; }
    public String getKind() { return kind; }
    public int getOccurrenceCount() { return occurrenceCount; }
}
