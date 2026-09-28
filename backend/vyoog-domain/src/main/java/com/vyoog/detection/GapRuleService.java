package com.vyoog.detection;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The one place that knows "a rule with no {@link GapRule} row yet is enabled by
 * default" (VYB-0164) — {@link com.vyoog.detection.DetectionSweepService} and the
 * rules API both need that same answer, and it's a business rule, not a detail either
 * call site should restate.
 */
@Service
public class GapRuleService {

    private final GapRuleRepository gapRules;

    public GapRuleService(GapRuleRepository gapRules) {
        this.gapRules = gapRules;
    }

    public Map<String, Boolean> enabledByRuleKey() {
        return gapRules.findAll().stream().collect(Collectors.toMap(GapRule::getRuleKey, GapRule::isEnabled));
    }

    public boolean isEnabled(String ruleKey) {
        return gapRules.findById(ruleKey).map(GapRule::isEnabled).orElse(true);
    }

    /** VYB-0164 AC2: disabling never deletes history — this only ever touches the config row. */
    public GapRule setEnabled(String ruleKey, boolean enabled) {
        GapRule rule = gapRules.findById(ruleKey).orElseGet(() -> new GapRule(ruleKey, true));
        rule.setEnabled(enabled);
        return gapRules.save(rule);
    }

    public List<String> disabledRuleKeys() {
        return gapRules.findAll().stream().filter(r -> !r.isEnabled()).map(GapRule::getRuleKey).toList();
    }

    /** VYB-0615 AC2: the confidence floor below which a rule's candidates are suppressed entirely. */
    public java.math.BigDecimal getThreshold(String ruleKey) {
        return gapRules.findById(ruleKey).map(GapRule::getThreshold).orElse(null);
    }

    /** VYB-0610 AC1/VYB-0652: an administrator adjusts a rule's threshold; existing findings aren't touched here — the caller states the effect first (VYB-0652 AC1). */
    public GapRule setThreshold(String ruleKey, java.math.BigDecimal threshold) {
        GapRule rule = gapRules.findById(ruleKey).orElseGet(() -> new GapRule(ruleKey, true));
        rule.setThreshold(threshold);
        return gapRules.save(rule);
    }
}
