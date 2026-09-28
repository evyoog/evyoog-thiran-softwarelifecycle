package com.vyoog.savedview;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A named set of requirement-grid filters, belonging to one person.
 *
 * <p>Every field is a parameter {@code GET /requirements} accepts. Nothing here can
 * express a filter the server cannot execute — a view narrowing only the rows already
 * loaded would show a count that contradicts the paginated total beside it.
 */
@Entity
@Table(name = "saved_view")
public class SavedView {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false)
    private String name;

    private String status;
    private String priority;
    private String type;

    @Column(name = "title_contains")
    private String titleContains;

    @Column(name = "capability_id")
    private UUID capabilityId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected SavedView() {}

    public SavedView(UUID ownerId, String name, String status, String priority, String type,
                     String titleContains, UUID capabilityId) {
        this.ownerId = ownerId;
        this.name = name;
        this.status = status;
        this.priority = priority;
        this.type = type;
        this.titleContains = titleContains;
        this.capabilityId = capabilityId;
    }

    /** Saving over an existing name replaces its filters rather than adding a second view with the same label. */
    public void replaceFilters(String status, String priority, String type, String titleContains, UUID capabilityId) {
        this.status = status;
        this.priority = priority;
        this.type = type;
        this.titleContains = titleContains;
        this.capabilityId = capabilityId;
    }

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public String getPriority() { return priority; }
    public String getType() { return type; }
    public String getTitleContains() { return titleContains; }
    public UUID getCapabilityId() { return capabilityId; }
    public Instant getCreatedAt() { return createdAt; }
}
