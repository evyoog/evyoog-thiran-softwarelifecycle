package com.vyoog.defect;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * VYB-0320: severity, environment and the requirement it traces to — or doesn't, in
 * which case {@link #isUntraced} says so rather than leaving a caller to notice a null
 * (AC1). {@code developerId}/{@code testerId} (V008) are VYB-0322 AC2's "the routing
 * is recorded" — queryable columns, not only a notification that fires once and
 * leaves no durable trace of who this went to.
 */
@Entity
@Table(name = "defect")
public class Defect {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String key;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DefectSeverity severity;

    @Column(name = "requirement_id")
    private UUID requirementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "found_in", nullable = false)
    private FoundIn foundIn;

    @Enumerated(EnumType.STRING)
    @Column(name = "root_cause")
    private RootCause rootCause;

    @Column(name = "developer_id")
    private UUID developerId;

    @Column(name = "tester_id")
    private UUID testerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DefectState state = DefectState.OPEN;

    @Column(name = "raised_at", nullable = false)
    private Instant raisedAt = Instant.now();

    protected Defect() {}

    public Defect(String key, String title, DefectSeverity severity, UUID requirementId,
                  FoundIn foundIn, UUID developerId, UUID testerId) {
        this.key = key;
        this.title = title;
        this.severity = severity;
        this.requirementId = requirementId;
        this.foundIn = foundIn;
        this.developerId = developerId;
        this.testerId = testerId;
    }

    public boolean isUntraced() { return requirementId == null; }

    /** VYB-0321 AC1: closure is refused without a classification. */
    public void close() {
        if (rootCause == null) {
            throw new IllegalStateException("A defect needs a root cause before it can close");
        }
        this.state = DefectState.CLOSED;
    }

    public void classify(RootCause rootCause) { this.rootCause = rootCause; }
    public void markFixed() { this.state = DefectState.FIXED; }

    public UUID getId() { return id; }
    public String getKey() { return key; }
    public String getTitle() { return title; }
    public DefectSeverity getSeverity() { return severity; }
    public UUID getRequirementId() { return requirementId; }
    public FoundIn getFoundIn() { return foundIn; }
    public RootCause getRootCause() { return rootCause; }
    public UUID getDeveloperId() { return developerId; }
    public UUID getTesterId() { return testerId; }
    public DefectState getState() { return state; }
    public Instant getRaisedAt() { return raisedAt; }
}
