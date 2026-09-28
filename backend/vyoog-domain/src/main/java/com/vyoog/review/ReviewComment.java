package com.vyoog.review;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * VYB-0306: a comment against the round, or against one requirement within it
 * ({@code requirementId} null vs. set). AC1 — this table is untouched by
 * {@link Review#close}, so a comment survives the round closing exactly by virtue of
 * nothing here ever deleting it.
 */
@Entity
@Table(name = "review_comment")
public class ReviewComment {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    @Column(name = "requirement_id")
    private UUID requirementId;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ReviewComment() {}

    public ReviewComment(UUID reviewId, UUID requirementId, UUID authorId, String body) {
        this.reviewId = reviewId;
        this.requirementId = requirementId;
        this.authorId = authorId;
        this.body = body;
    }

    public UUID getId() { return id; }
    public UUID getReviewId() { return reviewId; }
    public UUID getRequirementId() { return requirementId; }
    public UUID getAuthorId() { return authorId; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }
}
