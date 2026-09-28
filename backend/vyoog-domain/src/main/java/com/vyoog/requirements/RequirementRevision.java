package com.vyoog.requirements;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * An immutable snapshot of a requirement's content at one revision (VYB-0112).
 *
 * <p>Written once, on creation and on every material save. Never updated, never
 * deleted — there is deliberately no setter here beyond the constructor.
 */
@Entity
@Table(name = "requirement_revision")
public class RequirementRevision {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "requirement_id", nullable = false)
    private UUID requirementId;

    @Column(nullable = false)
    private int revision;

    @Column(nullable = false, columnDefinition = "text")
    private String statement;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private String priority;

    @Column(name = "capability_id")
    private UUID capabilityId;

    @Column(name = "changed_by")
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt = Instant.now();

    @Column(name = "change_reason")
    private String changeReason;

    protected RequirementRevision() {}

    public RequirementRevision(UUID requirementId, int revision, String statement, String title,
                                String type, String status, String priority, UUID capabilityId,
                                UUID changedBy, String changeReason) {
        this.requirementId = requirementId;
        this.revision = revision;
        this.statement = statement;
        this.title = title;
        this.type = type;
        this.status = status;
        this.priority = priority;
        this.capabilityId = capabilityId;
        this.changedBy = changedBy;
        this.changeReason = changeReason;
    }

    public UUID getId() { return id; }
    public UUID getRequirementId() { return requirementId; }
    public int getRevision() { return revision; }
    public String getStatement() { return statement; }
    public String getTitle() { return title; }
    public String getType() { return type; }
    public String getStatus() { return status; }
    public String getPriority() { return priority; }
    public UUID getCapabilityId() { return capabilityId; }
    public UUID getChangedBy() { return changedBy; }
    public Instant getChangedAt() { return changedAt; }
    public String getChangeReason() { return changeReason; }
}
