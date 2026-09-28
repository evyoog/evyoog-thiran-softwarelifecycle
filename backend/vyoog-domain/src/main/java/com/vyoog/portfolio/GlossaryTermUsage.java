package com.vyoog.portfolio;

import jakarta.persistence.*;
import java.util.UUID;

/**
 * VYB-0102 AC2 / VYB-0103: one application's use of a term (V004 migration — the
 * baseline's {@code glossary_term} had no per-application layer at all, which is
 * what made "flag a term defined differently in two applications" impossible to
 * build as specified; this table is that layer). {@code definition == null} means
 * "uses the canonical definition unchanged" — most rows will be this.
 */
@Entity
@Table(name = "glossary_term_usage")
public class GlossaryTermUsage {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "term_id", nullable = false)
    private UUID termId;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(columnDefinition = "text")
    private String definition;

    protected GlossaryTermUsage() {}

    public GlossaryTermUsage(UUID termId, UUID applicationId, String definition) {
        this.termId = termId;
        this.applicationId = applicationId;
        this.definition = definition;
    }

    public UUID getId() { return id; }
    public UUID getTermId() { return termId; }
    public UUID getApplicationId() { return applicationId; }
    public String getDefinition() { return definition; }
    public void setDefinition(String definition) { this.definition = definition; }
}
