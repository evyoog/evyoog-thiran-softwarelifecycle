package com.vyoog.trace;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A directed, typed edge in the trace graph (VYB-0140). {@code reviewedAtRevision}
 * (VYB-0141) is the upstream ({@code from}) object's revision at the moment this link
 * was last reviewed — it is what the "suspect link" detector (VYB-0159, a later
 * session) compares against the upstream's *current* revision.
 */
@Entity
@Table(name = "trace_link")
public class TraceLink {

    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_type", nullable = false)
    private TraceObjectType fromType;

    @Column(name = "from_id", nullable = false)
    private UUID fromId;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_type", nullable = false)
    private TraceObjectType toType;

    @Column(name = "to_id", nullable = false)
    private UUID toId;

    @Enumerated(EnumType.STRING)
    @Column(name = "link_type", nullable = false)
    private TraceLinkType linkType;

    @Column(name = "reviewed_at_revision")
    private Integer reviewedAtRevision;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected TraceLink() {}

    public TraceLink(TraceObjectType fromType, UUID fromId, TraceObjectType toType, UUID toId,
                      TraceLinkType linkType, UUID createdBy) {
        this.fromType = fromType;
        this.fromId = fromId;
        this.toType = toType;
        this.toId = toId;
        this.linkType = linkType;
        this.createdBy = createdBy;
    }

    /** VYB-0141 AC2: updatable only through this explicit review action, never a generic setter. */
    public void markReviewedAt(int upstreamRevision) {
        this.reviewedAtRevision = upstreamRevision;
    }

    public UUID getId() { return id; }
    public TraceObjectType getFromType() { return fromType; }
    public UUID getFromId() { return fromId; }
    public TraceObjectType getToType() { return toType; }
    public UUID getToId() { return toId; }
    public TraceLinkType getLinkType() { return linkType; }
    public Integer getReviewedAtRevision() { return reviewedAtRevision; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
