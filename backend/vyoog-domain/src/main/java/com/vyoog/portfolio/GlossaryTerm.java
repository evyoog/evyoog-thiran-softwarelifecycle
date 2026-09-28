package com.vyoog.portfolio;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/** VYB-0102: the canonical entry. Applications may override its definition — see {@link GlossaryTermUsage}. */
@Entity
@Table(name = "glossary_term")
public class GlossaryTerm {

    @Id
    @GeneratedValue
    private UUID id;

    @NotBlank
    @Column(nullable = false, unique = true)
    private String term;

    @NotBlank
    @Column(nullable = false, columnDefinition = "text")
    private String definition;

    @Column(name = "owner_id")
    private UUID ownerId;

    protected GlossaryTerm() {}

    public GlossaryTerm(String term, String definition) {
        this.term = term;
        this.definition = definition;
    }

    public UUID getId() { return id; }
    public String getTerm() { return term; }
    public String getDefinition() { return definition; }
    public void setDefinition(String definition) { this.definition = definition; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
}
