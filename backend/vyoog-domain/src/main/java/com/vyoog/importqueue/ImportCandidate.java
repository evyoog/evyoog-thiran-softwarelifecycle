package com.vyoog.importqueue;

import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * VYB-0632–0637: one extracted candidate requirement. {@code originalText} (V006) is
 * fixed at extraction and never edited (VYB-0635 AC1 — the source document doesn't
 * change); {@code statement} is what the user edits before import (AC2 — it's what
 * actually gets imported). {@code flags} carries whatever the lint/duplicate/capability
 * proposal step found — JSON, not more columns, because its shape varies candidate to
 * candidate the same way {@code AuditEvent.before/after} already do for audit rows.
 */
@Entity
@Table(name = "import_candidate")
public class ImportCandidate {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    private String tag;

    @Column(nullable = false, columnDefinition = "text")
    private String statement;

    @Column(name = "original_text", columnDefinition = "text")
    private String originalText;

    @Column(name = "source_location")
    private String sourceLocation;

    @Column(name = "capability_id")
    private UUID capabilityId;

    // D12: a candidate is placed at one level, exactly like the requirement it becomes.
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "application_id")
    private UUID applicationId;

    @Column(name = "capability_confirmed", nullable = false)
    private boolean capabilityConfirmed;

    @Column(name = "criteria_count", nullable = false)
    private short criteriaCount;

    @Column(name = "quality_score")
    private Short qualityScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String flags;

    @Column(nullable = false)
    private boolean selected;

    @Column(name = "import_reason")
    private String importReason;

    @Column(name = "committed_requirement_id")
    private UUID committedRequirementId;

    /** VYB-0666: an AI proposal, mirroring {@link #capabilityId}/{@link #capabilityConfirmed} exactly. */
    @Column(name = "proposed_type")
    private String proposedType;

    @Column(name = "type_confirmed", nullable = false)
    private boolean typeConfirmed;

    /**
     * VYB-0630 AI enrichment: the human-confirmed final acceptance-criteria text list
     * (JSON array of strings) — null until confirmed. Extraction's own criteria live in
     * {@link #flags} (briefAcceptanceCriteria) and commit uses those when this is null,
     * so a batch nobody hand-confirmed still lands with criteria.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "accepted_criteria", columnDefinition = "jsonb")
    private String acceptedCriteria;

    /**
     * VYB-0630 AI enrichment: the human-confirmed final trace-link list (JSON array of
     * {@code {"requirementId":"...","linkType":"..."}}) — null until confirmed.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "accepted_trace_links", columnDefinition = "jsonb")
    private String acceptedTraceLinks;

    protected ImportCandidate() {}

    public ImportCandidate(UUID batchId, String tag, String statement, String originalText, String sourceLocation) {
        this.batchId = batchId;
        this.tag = tag;
        this.statement = statement;
        this.originalText = originalText;
        this.sourceLocation = sourceLocation;
    }

    /** VYB-0635: only the editable copy moves; {@link #originalText} is untouched. */
    public void editText(String newStatement) { this.statement = newStatement; }

    public void proposeCapability(UUID capabilityId) {
        this.capabilityId = capabilityId;
        this.capabilityConfirmed = false; // VYB-0634 AC1: a proposal is not a confirmation
    }

    /** VYB-0634: the human confirms the proposed capability before import can happen. */
    public void confirmCapability(UUID capabilityId) {
        this.capabilityId = capabilityId;
        this.productId = null;
        this.applicationId = null;
        this.capabilityConfirmed = true;
    }

    /**
     * D12: confirms a placement at whichever level the human chose.
     *
     * <p>Counts as a confirmation for every level including product and application —
     * choosing "this whole product" is a decision somebody made, not a proposal left
     * outstanding, and leaving it unconfirmed would block the import for no reason.
     */
    public void confirmPlacement(com.vyoog.requirements.Placement placement) {
        this.productId = placement.productId();
        this.applicationId = placement.applicationId();
        this.capabilityId = placement.capabilityId();
        this.capabilityConfirmed = placement.isPlaced();
    }

    public com.vyoog.requirements.Placement getPlacement() {
        return com.vyoog.requirements.Placement.of(productId, applicationId, capabilityId);
    }

    public void setLintResult(short criteriaCount, Short qualityScore, String flagsJson) {
        this.criteriaCount = criteriaCount;
        this.qualityScore = qualityScore;
        this.flags = flagsJson;
    }

    /** VYB-0666: a proposal, not a confirmation — mirrors {@link #proposeCapability}. */
    public void proposeType(String type) {
        this.proposedType = type;
        this.typeConfirmed = false;
    }

    /** VYB-0666: the human confirms (or overrides, then confirms) the proposed type before commit uses it. */
    public void confirmType(String type) {
        this.proposedType = type;
        this.typeConfirmed = true;
    }

    /** VYB-0630 AI enrichment: a proposal is not a confirmation until this is called — mirrors {@link #confirmType}. */
    public void confirmAcceptanceCriteria(String criteriaJson) {
        this.acceptedCriteria = criteriaJson;
    }

    public void confirmTraceLinks(String linksJson) {
        this.acceptedTraceLinks = linksJson;
    }

    public void setCriteriaCount(short count) { this.criteriaCount = count; }

    public void select(boolean selected) { this.selected = selected; }

    /** VYB-0637 AC2: set before commit, so a flagged duplicate can go through with a stated reason. */
    public void setImportReason(String reason) { this.importReason = reason; }

    /** VYB-0637 AC2: importing anyway past a flagged duplicate requires a stated reason. */
    public void markCommitted(UUID requirementId, String importReason) {
        this.committedRequirementId = requirementId;
        this.importReason = importReason;
    }

    public UUID getId() { return id; }
    public UUID getBatchId() { return batchId; }
    public String getTag() { return tag; }
    public String getStatement() { return statement; }
    public String getOriginalText() { return originalText; }
    public String getSourceLocation() { return sourceLocation; }
    public UUID getCapabilityId() { return capabilityId; }
    public UUID getProductId() { return productId; }
    public UUID getApplicationId() { return applicationId; }
    public boolean isCapabilityConfirmed() { return capabilityConfirmed; }
    public short getCriteriaCount() { return criteriaCount; }
    public Short getQualityScore() { return qualityScore; }
    public String getFlags() { return flags; }
    public boolean isSelected() { return selected; }
    public String getImportReason() { return importReason; }
    public UUID getCommittedRequirementId() { return committedRequirementId; }
    public String getProposedType() { return proposedType; }
    public boolean isTypeConfirmed() { return typeConfirmed; }
    public String getAcceptedCriteria() { return acceptedCriteria; }
    public String getAcceptedTraceLinks() { return acceptedTraceLinks; }
}
