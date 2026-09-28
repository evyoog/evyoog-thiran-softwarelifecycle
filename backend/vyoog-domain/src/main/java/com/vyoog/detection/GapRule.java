package com.vyoog.detection;

import jakarta.persistence.*;

/**
 * Per-rule configuration (VYB-0164). A rule with no row here yet is enabled by
 * default — {@code gap_rule_template} is seeded for all twelve rules on day one, but
 * nobody has to explicitly opt in before a rule starts working.
 */
@Entity
@Table(name = "gap_rule")
public class GapRule {

    @Id
    @Column(name = "rule_key")
    private String ruleKey;

    @Column(nullable = false)
    private boolean enabled = true;

    private java.math.BigDecimal threshold;

    protected GapRule() {}

    public GapRule(String ruleKey, boolean enabled) {
        this.ruleKey = ruleKey;
        this.enabled = enabled;
    }

    public String getRuleKey() { return ruleKey; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public java.math.BigDecimal getThreshold() { return threshold; }
    public void setThreshold(java.math.BigDecimal threshold) { this.threshold = threshold; }
}
