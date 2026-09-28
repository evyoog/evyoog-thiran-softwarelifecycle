package com.vyoog.evidence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * VYB-0310: a test case, identified by a key the CI system already uses — not one we
 * allocate — for every row created via CI ingestion. VYB-0363 (session 14) adds a
 * second, human-facing creation path: drafting a test case as a proposal directly from
 * a requirement, before CI has ever run anything against it. {@code status}
 * distinguishes the two: {@code INGESTED} (the default — accurate for every row CI
 * ingestion has ever created) vs {@code DRAFT} (created by a person, not yet backed by
 * a real test run). VYB-0824 adds {@code description} (steps/what to check) to the
 * human path only — CI ingestion never supplies one, so it stays null for those rows.
 * VYB-0827 adds {@code category} — whether an accepted AI suggestion tested the
 * requirement alone ({@code INDIVIDUAL}) or together with something it's trace-linked
 * to ({@code DEPENDENCY}); null for manual drafts and every ingested row, since neither
 * has one to record. A deliberately separate enum from {@code
 * com.vyoog.ai.TestCaseGenerator.Category} — same two values, but this entity has no
 * reason to depend on the AI package for its own persisted vocabulary.
 */
@Entity
@Table(name = "test_case")
public class TestCase {

    public enum Status { DRAFT, INGESTED }
    public enum Category { INDIVIDUAL, DEPENDENCY }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String key;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    private Category category;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status = Status.INGESTED;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected TestCase() {}

    /** The CI-ingestion path (VYB-0310) — unchanged, still defaults to {@code INGESTED}. */
    public TestCase(String key, String title) {
        this.key = key;
        this.title = title;
    }

    /** VYB-0363/0824/0827: a person drafting a test case as a proposal, not CI reporting one that ran. */
    public TestCase(String key, String title, String description, Category category, Status status, UUID createdBy) {
        this.key = key;
        this.title = title;
        this.description = description;
        this.category = category;
        this.status = status;
        this.createdBy = createdBy;
    }

    /** VYB-0828: a person correcting or expanding a test case already on the register — title/description/category, nothing else. */
    public void edit(String title, String description, Category category) {
        this.title = title;
        this.description = description;
        this.category = category;
    }

    public UUID getId() { return id; }
    public String getKey() { return key; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public Category getCategory() { return category; }
    public Status getStatus() { return status; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
