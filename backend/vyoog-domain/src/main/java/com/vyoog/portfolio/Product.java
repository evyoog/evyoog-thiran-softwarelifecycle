package com.vyoog.portfolio;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue
    private UUID id;

    @NotBlank
    @Column(nullable = false)
    private String key;

    @NotBlank
    @Column(nullable = false)
    private String name;

    /** The italic line under the name on a product card, e.g. "Resource Intelligence". */
    private String vertical;

    /** One sentence — what this product optimises, manages, or enables. */
    @Column(columnDefinition = "text")
    private String purpose;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "lifecycle_status", nullable = false)
    private String lifecycleStatus = "IN_DEVELOPMENT";

    /** A fixed icon key — see V011 for the exact CHECK-constrained set. */
    @Column(nullable = false)
    private String mark = "box";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected Product() {}

    public Product(String key, String name) {
        this.key = key;
        this.name = name;
    }

    public UUID getId() { return id; }
    public String getKey() { return key; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getVertical() { return vertical; }
    public void setVertical(String vertical) { this.vertical = vertical; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public String getLifecycleStatus() { return lifecycleStatus; }
    public void setLifecycleStatus(String lifecycleStatus) { this.lifecycleStatus = lifecycleStatus; }
    public String getMark() { return mark; }
    public void setMark(String mark) { this.mark = mark; }
    public boolean isArchived() { return archivedAt != null; }
    public void archive() { this.archivedAt = Instant.now(); }
}
