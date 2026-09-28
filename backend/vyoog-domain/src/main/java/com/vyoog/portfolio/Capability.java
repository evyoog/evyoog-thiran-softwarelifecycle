package com.vyoog.portfolio;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;

/** Every capability belongs to exactly one application (VYB-0100). */
@Entity
@Table(name = "capability")
public class Capability {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @NotBlank
    @Column(nullable = false)
    private String name;

    private String code;

    /** VYB-0832: same shape as {@code Application.description} — nullable free text. */
    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected Capability() {}

    public Capability(UUID applicationId, String name) {
        this.applicationId = applicationId;
        this.name = name;
    }

    public UUID getId() { return id; }
    public UUID getApplicationId() { return applicationId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public boolean isArchived() { return archivedAt != null; }
    public void archive() { this.archivedAt = Instant.now(); }
}
