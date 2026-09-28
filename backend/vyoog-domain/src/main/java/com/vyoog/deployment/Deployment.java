package com.vyoog.deployment;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** VYB-0481: one build's arrival at one environment, ingested from CI. */
@Entity
@Table(name = "deployment")
public class Deployment {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    @Column(name = "build_label", nullable = false)
    private String buildLabel;

    @Column(name = "deployed_at", nullable = false)
    private Instant deployedAt = Instant.now();

    @Column(nullable = false)
    private boolean succeeded = true;

    protected Deployment() {}

    public Deployment(UUID environmentId, String buildLabel, boolean succeeded) {
        this.environmentId = environmentId;
        this.buildLabel = buildLabel;
        this.succeeded = succeeded;
    }

    public UUID getId() { return id; }
    public UUID getEnvironmentId() { return environmentId; }
    public String getBuildLabel() { return buildLabel; }
    public Instant getDeployedAt() { return deployedAt; }
    public boolean isSucceeded() { return succeeded; }
}
