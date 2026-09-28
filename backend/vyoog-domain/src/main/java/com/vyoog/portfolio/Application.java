package com.vyoog.portfolio;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/**
 * Every application belongs to exactly one product (VYB-0100). Deleting a product
 * with applications underneath it is refused at the service layer, not here.
 */
@Entity
@Table(name = "application")
public class Application {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "archived_at")
    private java.time.Instant archivedAt;

    protected Application() {}

    public Application(UUID productId, String name) {
        this.productId = productId;
        this.name = name;
    }

    public UUID getId() { return id; }
    public UUID getProductId() { return productId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isArchived() { return archivedAt != null; }
    public void archive() { this.archivedAt = java.time.Instant.now(); }
}
