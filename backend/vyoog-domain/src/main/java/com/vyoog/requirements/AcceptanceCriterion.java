package com.vyoog.requirements;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/** An ordered child of a requirement (VYB-0114). Order is stable across reads. */
@Entity
@Table(name = "acceptance_criterion")
public class AcceptanceCriterion {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "requirement_id", nullable = false)
    private UUID requirementId;

    @Column(nullable = false)
    private short ordinal;

    @NotBlank
    @Column(nullable = false, columnDefinition = "text")
    private String text;

    protected AcceptanceCriterion() {}

    public AcceptanceCriterion(UUID requirementId, short ordinal, String text) {
        this.requirementId = requirementId;
        this.ordinal = ordinal;
        this.text = text;
    }

    public UUID getId() { return id; }
    public UUID getRequirementId() { return requirementId; }
    public short getOrdinal() { return ordinal; }
    public void setOrdinal(short ordinal) { this.ordinal = ordinal; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
}
