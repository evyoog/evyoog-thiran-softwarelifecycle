package com.vyoog.evidence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * VYB-0311: one CI build's worth of results. VYB-0923 adds manual runs of a test suite to the same
 * table ({@code kind}, {@code status}, {@code suite_id}, ...); those columns are written by
 * {@code TestManagementService}, and a CI row keeps the database defaults (kind CI, status COMPLETED).
 */
@Entity
@Table(name = "test_run")
public class TestRun {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "build_label")
    private String buildLabel;

    @Column(name = "started_at")
    private Instant startedAt = Instant.now();

    private String source;

    protected TestRun() {}

    public TestRun(String buildLabel, String source) {
        this.buildLabel = buildLabel;
        this.source = source;
    }

    public UUID getId() { return id; }
    public String getBuildLabel() { return buildLabel; }
    public String getSource() { return source; }
}
