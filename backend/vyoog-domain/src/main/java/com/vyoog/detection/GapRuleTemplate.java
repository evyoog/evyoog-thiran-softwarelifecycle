package com.vyoog.detection;

import jakarta.persistence.*;

/** Global, seeded reference data (V001__baseline.sql) — the twelve detector definitions. */
@Entity
@Table(name = "gap_rule_template")
public class GapRuleTemplate {

    @Id
    private String key;

    private String name;
    private String technique;
    private String severity;
    private String description;
    private short phase;

    protected GapRuleTemplate() {}

    public String getKey() { return key; }
    public String getName() { return name; }
    public String getTechnique() { return technique; }
    public String getSeverity() { return severity; }
    public String getDescription() { return description; }
    public short getPhase() { return phase; }
}
