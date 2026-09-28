package com.vyoog.brief;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * VYB-0450/0455: a generated markdown brief, persisted with the content itself —
 * "retrievable unchanged later" (AC2) means this row, not a re-generation, is the
 * source of truth for what a developer was actually handed. {@code stale} is a
 * stored flag rather than derived — unlike most predicates in this codebase — because
 * {@link com.vyoog.requirements.RequirementService} sets it explicitly the moment an
 * in-scope requirement's revision moves (VYB-0456 AC1: "within one detection cycle"),
 * and there's no cheaper way to answer "which briefs did this edit affect" than that.
 */
@Entity
@Table(name = "brief")
public class Brief {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BriefTarget target;

    @Column(name = "developer_id", nullable = false)
    private UUID developerId;

    @Column(name = "baseline_id")
    private UUID baselineId;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "generated_by")
    private UUID generatedBy;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt = Instant.now();

    @Column(nullable = false)
    private boolean stale;

    protected Brief() {}

    public Brief(UUID applicationId, BriefTarget target, UUID developerId, String content, UUID generatedBy) {
        this.applicationId = applicationId;
        this.target = target;
        this.developerId = developerId;
        this.content = content;
        this.generatedBy = generatedBy;
    }

    public void markStale() { this.stale = true; }

    public UUID getId() { return id; }
    public UUID getApplicationId() { return applicationId; }
    public BriefTarget getTarget() { return target; }
    public UUID getDeveloperId() { return developerId; }
    public String getContent() { return content; }
    public UUID getGeneratedBy() { return generatedBy; }
    public Instant getGeneratedAt() { return generatedAt; }
    public boolean isStale() { return stale; }
}
