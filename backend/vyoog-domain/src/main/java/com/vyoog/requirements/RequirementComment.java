package com.vyoog.requirements;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;

/** VYB-0124: threaded comments — flat today, since the baseline table has no parent_id for replies. */
@Entity
@Table(name = "requirement_comment")
public class RequirementComment {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "requirement_id", nullable = false)
    private UUID requirementId;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @NotBlank
    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected RequirementComment() {}

    public RequirementComment(UUID requirementId, UUID authorId, String body) {
        this.requirementId = requirementId;
        this.authorId = authorId;
        this.body = body;
    }

    public UUID getId() { return id; }
    public UUID getRequirementId() { return requirementId; }
    public UUID getAuthorId() { return authorId; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }
}
