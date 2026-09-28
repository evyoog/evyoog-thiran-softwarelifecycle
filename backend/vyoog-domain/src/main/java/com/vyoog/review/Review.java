package com.vyoog.review;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A review round (VYB-0300). "Stale" (AC2 — editing a requirement mid-round marks the
 * round stale) is deliberately not a column here: it's a comparison between each
 * {@code review_item.revision} (frozen at open time) and that requirement's current
 * revision, computed at read time by {@link ReviewService#isStale} — a derived
 * predicate, the same choice already made for requirement verification (VYB-0117) and
 * suspect trace links (VYB-0159), rather than a flag that could silently drift from
 * the rows it's supposed to summarise.
 */
@Entity
@Table(name = "review")
public class Review {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column(name = "scope_ref")
    private String scopeRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewState state = ReviewState.OPEN;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt = Instant.now();

    @Column(name = "closes_at")
    private Instant closesAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected Review() {}

    public Review(String title, String scopeRef) {
        this.title = title;
        this.scopeRef = scopeRef;
    }

    void close() {
        if (state != ReviewState.OPEN) {
            throw new IllegalStateException("Round '%s' is already %s".formatted(title, state));
        }
        this.state = ReviewState.CLOSED;
        this.closedAt = Instant.now();
    }

    /**
     * VYB-0372 (session 14): this column existed since V001 with no getter, no setter,
     * and nothing anywhere ever writing to it — a genuinely dead column, not a hidden
     * feature. Opening it up here is what makes {@code closes_at} the one real
     * future-facing date the calendar had to work with (see docs/DECISIONS.md-adjacent
     * note in BUILD-REGISTER.md session 14: everything else on {@code release} etc. has
     * no target date at all).
     */
    public void setClosesAt(Instant closesAt) { this.closesAt = closesAt; }

    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public String getScopeRef() { return scopeRef; }
    public ReviewState getState() { return state; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getClosesAt() { return closesAt; }
    public Instant getClosedAt() { return closedAt; }
}
