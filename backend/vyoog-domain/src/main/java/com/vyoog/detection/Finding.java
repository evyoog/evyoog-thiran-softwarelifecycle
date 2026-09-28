package com.vyoog.detection;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A raised gap, reconciled by fingerprint across runs (VYB-0151). State changes only
 * through the methods below, each corresponding to one specific transition a human or
 * the reconciler can actually cause — there is no generic {@code setState}.
 */
@Entity
@Table(name = "finding")
public class Finding {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "rule_key", nullable = false)
    private String ruleKey;

    @Column(nullable = false, unique = true)
    private String fingerprint;

    @Column(name = "object_type", nullable = false)
    private String objectType;

    @Column(name = "object_id", nullable = false)
    private UUID objectId;

    @Column(nullable = false)
    private String severity;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String detail;

    @Column(columnDefinition = "text")
    private String suggestion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FindingState state = FindingState.OPEN;

    @Column(name = "dismiss_reason")
    private String dismissReason;

    @Column(name = "actioned_by")
    private UUID actionedBy;

    @Column(name = "actioned_at")
    private Instant actionedAt;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt = Instant.now();

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** VYB-0154: the object's revision this finding was last raised or refreshed against. */
    @Column(name = "object_revision")
    private Integer objectRevision;

    /** VYB-0615: AI detectors only — null for the twelve rule/graph rules. */
    @Column(precision = 4, scale = 3)
    private java.math.BigDecimal confidence;

    /** VYB-0616: "model + prompt version" as one string — see {@link Candidate#model}. */
    @Column
    private String model;

    protected Finding() {}

    Finding(Candidate c) {
        this.ruleKey = c.ruleKey();
        this.fingerprint = c.fingerprint();
        this.objectType = c.objectType();
        this.objectId = c.objectId();
        this.severity = c.severity();
        this.title = c.title();
        this.detail = c.detail();
        this.suggestion = c.suggestion();
        this.objectRevision = c.objectRevision();
        this.confidence = toBigDecimal(c.confidence());
        this.model = c.model();
    }

    private static java.math.BigDecimal toBigDecimal(Double d) {
        return d == null ? null : java.math.BigDecimal.valueOf(d);
    }

    /**
     * Reseen on a rerun (VYB-0152's mirror image): refresh content, not state.
     * Confidence/model are refreshed too — this is the same finding still being
     * actively produced, and VYB-0616 AC2 (provenance survives dismissal) is about
     * a finding no longer being reseen at all, not one whose content updates.
     */
    void refresh(Candidate c) {
        this.title = c.title();
        this.detail = c.detail();
        this.suggestion = c.suggestion();
        this.objectRevision = c.objectRevision();
        this.confidence = toBigDecimal(c.confidence());
        this.model = c.model();
        this.lastSeenAt = Instant.now();
    }

    /** VYB-0152: a completed run's candidates no longer include this fingerprint. */
    void resolve() {
        this.state = FindingState.RESOLVED;
        this.resolvedAt = Instant.now();
    }

    /** A candidate for a previously RESOLVED (or dismissed-then-changed) finding recurred. */
    void reopen(Candidate c) {
        this.state = FindingState.OPEN;
        refresh(c);
        this.resolvedAt = null;
    }

    /** VYB-0153: requires a reason, requires an actor. Enforced by the caller (FindingService). */
    void dismiss(String reason, UUID actor) {
        this.state = FindingState.DISMISSED;
        this.dismissReason = reason;
        this.actionedBy = actor;
        this.actionedAt = Instant.now();
    }

    void accept(UUID actor) {
        this.state = FindingState.ACCEPTED;
        this.actionedBy = actor;
        this.actionedAt = Instant.now();
    }

    /**
     * VYB-0224 AC2: a human can reopen a dismissed finding — distinct from
     * {@link #reopen(Candidate)}, which is the reconciler noticing a candidate recur.
     * {@code dismissReason} is left as history rather than cleared; it's still true
     * that someone dismissed this once, for this reason.
     */
    void reopenManually(UUID actor) {
        this.state = FindingState.OPEN;
        this.actionedBy = actor;
        this.actionedAt = Instant.now();
        this.resolvedAt = null;
    }

    /**
     * VYB-0654: the "other side" of a pairwise finding (duplicate/conflict), recovered
     * from the fingerprint rather than a second stored column — {@link
     * Candidate#fingerprint} already encodes it as the fourth {@code |}-joined field,
     * and a value derived from data already there can't drift from it.
     */
    public String getDiscriminator() {
        String[] parts = fingerprint.split("\\|", 4);
        return parts.length == 4 && !parts[3].isEmpty() ? parts[3] : null;
    }

    public UUID getId() { return id; }
    public String getRuleKey() { return ruleKey; }
    public String getFingerprint() { return fingerprint; }
    public String getObjectType() { return objectType; }
    public UUID getObjectId() { return objectId; }
    public String getSeverity() { return severity; }
    public String getTitle() { return title; }
    public String getDetail() { return detail; }
    public String getSuggestion() { return suggestion; }
    public FindingState getState() { return state; }
    public String getDismissReason() { return dismissReason; }
    public UUID getActionedBy() { return actionedBy; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Integer getObjectRevision() { return objectRevision; }
    public java.math.BigDecimal getConfidence() { return confidence; }
    public String getModel() { return model; }
}
