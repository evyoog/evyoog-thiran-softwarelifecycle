package com.vyoog.baseline;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * VYB-0470: an immutable freeze of every in-scope requirement's current revision.
 * {@code baseline_item} (V001__baseline.sql) has no surrogate key and nothing else
 * needs it as an object graph, so it's read/written as raw SQL in
 * {@link BaselineService} — the same choice already made for {@code review_item} and
 * {@code change_request_requirement}.
 */
@Entity
@Table(name = "baseline")
public class Baseline {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "release_id")
    private UUID releaseId;

    @Column(name = "frozen_at", nullable = false)
    private Instant frozenAt = Instant.now();

    @Column(name = "frozen_by")
    private UUID frozenBy;

    @Column(name = "gaps_at_freeze", nullable = false)
    private int gapsAtFreeze;

    protected Baseline() {}

    public Baseline(String name, UUID releaseId, UUID frozenBy, int gapsAtFreeze) {
        this.name = name;
        this.releaseId = releaseId;
        this.frozenBy = frozenBy;
        this.gapsAtFreeze = gapsAtFreeze;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public UUID getReleaseId() { return releaseId; }
    public Instant getFrozenAt() { return frozenAt; }
    public UUID getFrozenBy() { return frozenBy; }
    public int getGapsAtFreeze() { return gapsAtFreeze; }
}
