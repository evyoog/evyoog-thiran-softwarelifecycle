package com.vyoog.release;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** VYB-0475: every addition to and removal from a release's scope, with who and why. */
@Entity
@Table(name = "scope_movement")
public class ScopeMovement {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "release_id", nullable = false)
    private UUID releaseId;

    @Column(name = "requirement_id", nullable = false)
    private UUID requirementId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MovementDirection direction;

    private String reason;

    // Nullable at the DB (V001 puts no NOT NULL on moved_by) — VYB-0475 AC2's "no
    // movement without a recorded actor" is enforced by ReleaseService always
    // supplying one, the same choice already made for Baseline#frozenBy, rather than
    // a schema change to tighten a constraint nothing here has ever violated.
    @Column(name = "moved_by")
    private UUID movedBy;

    @Column(name = "moved_at", nullable = false)
    private Instant movedAt = Instant.now();

    protected ScopeMovement() {}

    public ScopeMovement(UUID releaseId, UUID requirementId, MovementDirection direction, String reason, UUID movedBy) {
        this.releaseId = releaseId;
        this.requirementId = requirementId;
        this.direction = direction;
        this.reason = reason;
        this.movedBy = movedBy;
    }

    public UUID getId() { return id; }
    public UUID getReleaseId() { return releaseId; }
    public UUID getRequirementId() { return requirementId; }
    public MovementDirection getDirection() { return direction; }
    public String getReason() { return reason; }
    public UUID getMovedBy() { return movedBy; }
    public Instant getMovedAt() { return movedAt; }
}
