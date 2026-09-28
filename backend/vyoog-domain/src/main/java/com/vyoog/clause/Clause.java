package com.vyoog.clause;

import jakarta.persistence.*;
import java.util.UUID;

/**
 * VYB-0611: a control clause from some external standard (SOC 2, ISO 27001, whatever
 * the tenant is compliant against). Nothing in this codebase ingests these from a real
 * source yet — that's an intake gap, not a detection one (see BUILD-REGISTER.md);
 * {@code UnmappedControlClauseDetector} works on whatever rows exist here.
 */
@Entity
@Table(name = "clause")
public class Clause {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String standard;

    private String section;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    protected Clause() {}

    public Clause(String standard, String section, String text) {
        this.standard = standard;
        this.section = section;
        this.text = text;
    }

    public UUID getId() { return id; }
    public String getStandard() { return standard; }
    public String getSection() { return section; }
    public String getText() { return text; }
}
