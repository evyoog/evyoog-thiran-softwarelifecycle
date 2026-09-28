package com.vyoog.changerequest;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * VYB-0390: a change against one or more approved requirements, with a rationale —
 * which requirements is {@code change_request_requirement} (V005), read/written via
 * {@link ChangeRequestService}, not mapped here, same as {@code review_item}.
 */
@Entity
@Table(name = "change_request")
public class ChangeRequest {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String key;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String rationale;

    @Column(name = "raised_by")
    private UUID raisedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChangeRequestState state = ChangeRequestState.OPEN;

    @Column(name = "impact_requirements", nullable = false)
    private int impactRequirements;

    @Column(name = "impact_apps", nullable = false)
    private int impactApps;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected ChangeRequest() {}

    public ChangeRequest(String key, String title, String rationale, UUID raisedBy) {
        this.key = key;
        this.title = title;
        this.rationale = rationale;
        this.raisedBy = raisedBy;
    }

    public void recordImpact(int requirements, int apps) {
        this.impactRequirements = requirements;
        this.impactApps = apps;
    }

    /** VYB-0393: state is one of OPEN, then a terminal decision, then (for an approved one) applied. */
    public void decide(boolean approve, UUID actor) {
        if (state != ChangeRequestState.OPEN) {
            throw new IllegalStateException("This change request is already " + state);
        }
        this.state = approve ? ChangeRequestState.APPROVED : ChangeRequestState.REJECTED;
        this.decidedBy = actor;
        this.decidedAt = Instant.now();
    }

    public void markApplied() {
        if (state != ChangeRequestState.APPROVED) {
            throw new IllegalStateException("Only an approved change request can be applied");
        }
        this.state = ChangeRequestState.APPLIED;
    }

    public UUID getId() { return id; }
    public String getKey() { return key; }
    public String getTitle() { return title; }
    public String getRationale() { return rationale; }
    public UUID getRaisedBy() { return raisedBy; }
    public ChangeRequestState getState() { return state; }
    public int getImpactRequirements() { return impactRequirements; }
    public int getImpactApps() { return impactApps; }
    public UUID getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
}
