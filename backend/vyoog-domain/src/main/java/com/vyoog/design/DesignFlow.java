package com.vyoog.design;

import jakarta.persistence.*;
import java.util.UUID;

/** VYB-0490: at most one per application — enforced twice, by {@code UNIQUE(application_id)} in the DB and checked first in {@link DesignService} for a clearer refusal message. */
@Entity
@Table(name = "design_flow")
public class DesignFlow {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "application_id", nullable = false, unique = true)
    private UUID applicationId;

    protected DesignFlow() {}

    public DesignFlow(UUID applicationId) { this.applicationId = applicationId; }

    public UUID getId() { return id; }
    public UUID getApplicationId() { return applicationId; }
}
