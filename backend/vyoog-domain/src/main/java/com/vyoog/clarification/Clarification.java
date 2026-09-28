package com.vyoog.clarification;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * VYB-0330: "fully specified during design and never built [until now]. It is the
 * highest-value remaining object." Always names a requirement (AC1) — there is no
 * constructor path that doesn't — and blocks by default (AC2).
 */
@Entity
@Table(name = "clarification")
public class Clarification {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "requirement_id", nullable = false)
    private UUID requirementId;

    @Column(nullable = false, columnDefinition = "text")
    private String question;

    @Column(name = "blocks_task", nullable = false)
    private boolean blocksTask;

    @Column(name = "raised_by", nullable = false)
    private UUID raisedBy;

    @Column(name = "raised_at", nullable = false)
    private Instant raisedAt = Instant.now();

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(columnDefinition = "text")
    private String answer;

    @Column(name = "answered_by")
    private UUID answeredBy;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClarificationState state = ClarificationState.OPEN;

    @Column(name = "resulted_in_change_request_id")
    private UUID resultedInChangeRequestId;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    /** VYB-0334 AC2's "the routing is recorded" mirror — who it actually went to, queryable, not only in the audit event's JSONB. */
    @Column(name = "escalated_to")
    private UUID escalatedTo;

    protected Clarification() {}

    public Clarification(UUID requirementId, String question, boolean blocksTask, UUID raisedBy, UUID assignedTo) {
        this.requirementId = requirementId;
        this.question = question;
        this.blocksTask = blocksTask;
        this.raisedBy = raisedBy;
        this.assignedTo = assignedTo;
    }

    /** VYB-0332 AC1: answering closes it. */
    public void answer(String answer, UUID answeredBy) {
        if (state != ClarificationState.OPEN) {
            throw new IllegalStateException("This clarification is already " + state);
        }
        this.answer = answer;
        this.answeredBy = answeredBy;
        this.answeredAt = Instant.now();
        this.state = ClarificationState.ANSWERED;
    }

    public void withdraw() {
        if (state != ClarificationState.OPEN) {
            throw new IllegalStateException("This clarification is already " + state);
        }
        this.state = ClarificationState.WITHDRAWN;
    }

    /** VYB-0333 AC1: the offered, never forced, change-request path. */
    public void linkChangeRequest(UUID changeRequestId) { this.resultedInChangeRequestId = changeRequestId; }

    public void markEscalated(UUID escalatedTo) {
        this.escalatedAt = Instant.now();
        this.escalatedTo = escalatedTo;
    }

    public UUID getId() { return id; }
    public UUID getRequirementId() { return requirementId; }
    public String getQuestion() { return question; }
    public boolean isBlocksTask() { return blocksTask; }
    public UUID getRaisedBy() { return raisedBy; }
    public Instant getRaisedAt() { return raisedAt; }
    public UUID getAssignedTo() { return assignedTo; }
    public String getAnswer() { return answer; }
    public UUID getAnsweredBy() { return answeredBy; }
    public Instant getAnsweredAt() { return answeredAt; }
    public ClarificationState getState() { return state; }
    public UUID getResultedInChangeRequestId() { return resultedInChangeRequestId; }
    public Instant getEscalatedAt() { return escalatedAt; }
    public UUID getEscalatedTo() { return escalatedTo; }
}
