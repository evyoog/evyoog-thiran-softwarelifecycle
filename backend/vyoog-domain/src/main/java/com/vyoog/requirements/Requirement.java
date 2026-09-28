package com.vyoog.requirements;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;

/**
 * The requirement aggregate (VYB-0110). The service layer, not this class, owns
 * revision-bumping policy (whether a save is material) and the audit trail — this
 * class only enforces what can never be a valid requirement regardless of caller.
 */
@Entity
@Table(name = "requirement")
public class Requirement {

    @Id
    @GeneratedValue
    private UUID id;

    /** Human-facing identifier, globally unique, e.g. VY-1042 (VYB-0110 AC1). */
    @Column(nullable = false)
    private String key;

    @Column(name = "capability_id")
    private UUID capabilityId;

    // D12: a requirement sits at one level. The database CHECK enforces that at most one
    // of these three is set; Placement is what stops the application ever trying.
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "application_id")
    private UUID applicationId;

    @Column(nullable = false)
    private String type = "FUNCTIONAL";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequirementStatus status = RequirementStatus.DRAFT;

    @Column(nullable = false)
    private String priority = "MEDIUM";

    @NotBlank
    @Column(nullable = false)
    private String title;

    @NotBlank
    @Column(nullable = false, columnDefinition = "text")
    private String statement;

    @Column(columnDefinition = "text")
    private String rationale;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "developer_id")
    private UUID developerId;

    @Column(name = "tester_id")
    private UUID testerId;

    @Column(name = "target_release_id")
    private UUID targetReleaseId;

    @Column(nullable = false)
    private int revision = 1;

    @Column(name = "quality_score")
    private Short qualityScore;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** VYB-0813: the status this requirement held immediately before its current one — null until the first transition. */
    @Column(name = "previous_status")
    private String previousStatus;

    /** VYB-0813: how many times this requirement has entered NEEDS_REVISION. */
    @Column(name = "revision_count", nullable = false)
    private int revisionCount = 0;

    /** VYB-0813: the reason given for the most recent transition — required for REVIEWED -> REJECTED/NEEDS_REVISION, optional otherwise. */
    @Column(columnDefinition = "text")
    private String reason;

    /** VYB-0813: who made the most recent status transition — distinct from updated_by, which covers content edits too. */
    @Column(name = "changed_by")
    private UUID changedBy;

    /** VYB-0813: when the most recent status transition happened. */
    @Column(name = "changed_at")
    private Instant changedAt;

    /**
     * VYB-0813: reserved for the deferred fork-a-new-version-on-editing-APPROVED
     * mechanism (see docs/DECISIONS.md D17) — always 1 until that is built. Editing an
     * APPROVED requirement is refused today exactly as it was before this column
     * existed; nothing increments it yet.
     */
    @Column(nullable = false)
    private int version = 1;

    protected Requirement() {}

    public Requirement(String key, String title, String statement, UUID createdBy) {
        this.key = key;
        this.title = title;
        this.statement = statement;
        this.createdBy = createdBy;
        this.updatedBy = createdBy;
    }

    /**
     * Sets the optional fields not covered by the constructor. Only valid immediately
     * after construction (revision 1, never yet saved) — this is not a general setter
     * and does not go through revision policy, because there is no prior revision to
     * revise away from at creation time.
     */
    void initialize(String type, String priority, Placement placement) {
        if (type != null) this.type = type;
        if (priority != null) this.priority = priority;
        place(placement);
    }

    /**
     * Moves the requirement to a placement, clearing the other two columns.
     *
     * <p>Assigning all three every time is the point: setting the new one without
     * clearing the others is what would violate the database CHECK, and doing it here
     * once means no caller can forget.
     */
    void place(Placement placement) {
        java.util.Objects.requireNonNull(placement,
            "A requirement is placed somewhere or explicitly Placement.unplaced() — never null.");
        this.productId = placement.productId();
        this.applicationId = placement.applicationId();
        this.capabilityId = placement.capabilityId();
    }

    /** D12: derived from which column is set, never stored — the two cannot disagree. */
    public PlacementLevel getPlacementLevel() {
        if (capabilityId != null) return PlacementLevel.CAPABILITY;
        if (applicationId != null) return PlacementLevel.APPLICATION;
        if (productId != null) return PlacementLevel.PRODUCT;
        return PlacementLevel.UNPLACED;
    }

    public Placement getPlacement() {
        return new Placement(getPlacementLevel(), productId, applicationId, capabilityId);
    }

    /**
     * Applies a material content change: new revision, new snapshot. VYB-0810: this
     * used to also drop a VERIFIED requirement back to APPROVED — VERIFIED no longer
     * exists as a status, so there is nothing left to demote here. The service layer
     * decides *whether* a change is material; by the time this runs, it already is.
     */
    void applyRevision(String title, String statement, String type, String priority,
                        UUID capabilityId, UUID changedBy) {
        this.title = title;
        this.statement = statement;
        this.type = type;
        this.priority = priority;
        this.capabilityId = capabilityId;
        this.revision += 1;
        this.updatedAt = Instant.now();
        this.updatedBy = changedBy;
    }

    /**
     * VYB-0121: bulk reassignment of metadata fields — priority, type, capability.
     * Deliberately not {@link #applyRevision}: these are administrative fields, not
     * content, so this does not bump the revision. Only the non-null arguments are
     * applied, matching the bulk-edit contract that every field defaults to "leave
     * unchanged" (VYB-0180 AC1).
     */
    void reassign(String priority, String type, UUID capabilityId, boolean touchCapability, UUID actor) {
        if (priority != null) this.priority = priority;
        if (type != null) this.type = type;
        if (touchCapability) this.capabilityId = capabilityId;
        this.updatedAt = Instant.now();
        this.updatedBy = actor;
    }

    void transitionTo(RequirementStatus target, UUID actor) {
        transitionTo(target, actor, null);
    }

    /**
     * VYB-0813: {@code reason} is recorded whether or not it is required for this
     * particular move — {@code RequirementService}/{@code BulkEditService} decide
     * *whether* one is required (REVIEWED -> REJECTED/NEEDS_REVISION) before calling
     * this; by the time this runs, that check has already passed.
     */
    void transitionTo(RequirementStatus target, UUID actor, String reason) {
        // Already there is not an illegal move. Without this a bulk edit that sets
        // status alongside priority threw "Cannot move VY-1 from DRAFT to DRAFT" for
        // every row already in that status — and because the throw happens before
        // reassign, those rows lost the priority and type changes the user did want
        // as well. The state machine governs where a requirement may go, not whether
        // asking for where it already is counts as an error.
        if (status == target) {
            return;
        }
        if (!status.canMoveTo(target)) {
            throw new IllegalStateException(
                "Invalid transition: %s -> %s is not allowed".formatted(status, target));
        }
        this.previousStatus = this.status.name();
        this.status = target;
        this.reason = reason;
        this.changedBy = actor;
        this.changedAt = Instant.now();
        if (target == RequirementStatus.NEEDS_REVISION) {
            this.revisionCount += 1;
        }
        this.updatedAt = Instant.now();
        this.updatedBy = actor;
    }

    public UUID getId() { return id; }
    public String getKey() { return key; }
    public String getTitle() { return title; }
    public String getStatement() { return statement; }
    public RequirementStatus getStatus() { return status; }
    public String getType() { return type; }
    public String getPriority() { return priority; }
    public int getRevision() { return revision; }
    public UUID getCapabilityId() { return capabilityId; }
    public UUID getProductId() { return productId; }
    public UUID getApplicationId() { return applicationId; }
    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public UUID getDeveloperId() { return developerId; }
    public void setDeveloperId(UUID developerId) { this.developerId = developerId; }
    public UUID getTesterId() { return testerId; }
    public void setTesterId(UUID testerId) { this.testerId = testerId; }
    public UUID getTargetReleaseId() { return targetReleaseId; }
    public void setTargetReleaseId(UUID targetReleaseId) { this.targetReleaseId = targetReleaseId; }
    public Short getQualityScore() { return qualityScore; }
    public void setQualityScore(Short qualityScore) { this.qualityScore = qualityScore; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public UUID getUpdatedBy() { return updatedBy; }
    public boolean isDeleted() { return deletedAt != null; }
    public String getPreviousStatus() { return previousStatus; }
    public int getRevisionCount() { return revisionCount; }
    public String getReason() { return reason; }
    public UUID getChangedBy() { return changedBy; }
    public Instant getChangedAt() { return changedAt; }
    public int getVersion() { return version; }

    /**
     * Soft delete. The row stays: revisions, trace links, audit entries and any brief that
     * already quoted this requirement all point at it, and a hard delete would either
     * cascade through that history or leave it dangling. Every read path in the register
     * already filters on {@code deleted_at IS NULL}, so this is what "gone" means here.
     */
    void markDeleted(UUID actor) {
        if (deletedAt != null) {
            return; // Deleting twice is not an error; it is the state the caller asked for.
        }
        this.deletedAt = Instant.now();
        this.updatedAt = Instant.now();
        this.updatedBy = actor;
    }
}
