package com.vyoog.evidence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** VYB-0311: one CI build's worth of results. */
@Entity
@Table(name = "test_run")
public class TestRun {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "build_label")
    private String buildLabel;

    @Column(name = "started_at", nullable = false)
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
