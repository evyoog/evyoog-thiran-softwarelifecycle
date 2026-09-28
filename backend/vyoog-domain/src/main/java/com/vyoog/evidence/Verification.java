package com.vyoog.evidence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * VYB-0312: bound to the requirement revision that was actually tested, not
 * whatever revision it happens to be when someone later looks — {@code
 * requirementRevision} is a snapshot taken at ingest time (AC1) and never updated, so
 * a later edit makes this evidence stale automatically (AC2) simply by no longer
 * matching {@code requirement.revision} — see the {@code requirement_verification_state}
 * view in V001__baseline.sql, which is exactly that comparison.
 */
@Entity
@Table(name = "verification")
public class Verification {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "requirement_id", nullable = false)
    private UUID requirementId;

    @Column(name = "requirement_revision", nullable = false)
    private int requirementRevision;

    @Column(name = "test_case_id")
    private UUID testCaseId;

    @Column(name = "test_run_id")
    private UUID testRunId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VerificationResult result;

    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt = Instant.now();

    protected Verification() {}

    public Verification(UUID requirementId, int requirementRevision, UUID testCaseId,
                         UUID testRunId, VerificationResult result) {
        this.requirementId = requirementId;
        this.requirementRevision = requirementRevision;
        this.testCaseId = testCaseId;
        this.testRunId = testRunId;
        this.result = result;
    }

    public UUID getId() { return id; }
    public UUID getRequirementId() { return requirementId; }
    public int getRequirementRevision() { return requirementRevision; }
    public UUID getTestCaseId() { return testCaseId; }
    public UUID getTestRunId() { return testRunId; }
    public VerificationResult getResult() { return result; }
    public Instant getVerifiedAt() { return verifiedAt; }
}
