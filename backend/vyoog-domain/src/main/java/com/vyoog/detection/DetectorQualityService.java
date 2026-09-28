package com.vyoog.detection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0617/0618: a detector's measured dismissal rate, computed from real {@code
 * finding} rows — not a stored/cached figure, since it would drift from reality the
 * moment someone dismissed or reopened one more finding.
 */
@Service
public class DetectorQualityService {

    private final JdbcTemplate jdbc;
    private final GapRuleService gapRules;

    public DetectorQualityService(JdbcTemplate jdbc, GapRuleService gapRules) {
        this.jdbc = jdbc;
        this.gapRules = gapRules;
    }

    public record DismissalRate(String ruleKey, long totalEverRaised, long dismissed, BigDecimal rate) {}

    /** VYB-0617 AC2: this deployment is one tenant, so "per tenant" is simply this whole table. */
    public List<DismissalRate> dismissalRateByRule() {
        return jdbc.query("""
            SELECT rule_key, count(*) AS total, count(*) FILTER (WHERE state = 'DISMISSED') AS dismissed
            FROM finding
            GROUP BY rule_key
            ORDER BY rule_key
            """,
            (rs, n) -> {
                long total = rs.getLong("total");
                long dismissed = rs.getLong("dismissed");
                BigDecimal rate = total == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(dismissed).divide(BigDecimal.valueOf(total), 3, RoundingMode.HALF_UP);
                return new DismissalRate(rs.getString("rule_key"), total, dismissed, rate);
            });
    }

    public record DismissalReasonCount(String reason, long count) {}

    /** VYB-0653 AC2: the most common reasons a human actually gave for dismissing this rule's findings. */
    public List<DismissalReasonCount> topDismissalReasons(String ruleKey, int limit) {
        return jdbc.query("""
            SELECT dismiss_reason, count(*) AS n FROM finding
            WHERE rule_key = ? AND state = 'DISMISSED' AND dismiss_reason IS NOT NULL
            GROUP BY dismiss_reason ORDER BY n DESC LIMIT ?
            """,
            (rs, n) -> new DismissalReasonCount(rs.getString("dismiss_reason"), rs.getLong("n")),
            ruleKey, limit);
    }

    public record NoisyDetectorResult(String ruleKey, BigDecimal rate, boolean disabled) {}

    /**
     * VYB-0618: an explicit administrative action, never run automatically — nothing
     * in this codebase calls this on a schedule or on startup, which is exactly what
     * AC2 ("existing tenants are not changed automatically") requires. It exists so a
     * newly-provisioned tenant's defaults *could* be seeded from it; this single-tenant
     * deployment has no such provisioning flow to hang it on (see BUILD-REGISTER.md).
     */
    public List<NoisyDetectorResult> applyNoisyDetectorDefaults() {
        BigDecimal ceiling = jdbc.queryForObject(
            "SELECT noisy_detector_dismissal_ceiling FROM app_config WHERE id = 1", BigDecimal.class);
        return dismissalRateByRule().stream()
            .filter(r -> ceiling != null && r.rate().compareTo(ceiling) > 0)
            .map(r -> {
                gapRules.setEnabled(r.ruleKey(), false);
                return new NoisyDetectorResult(r.ruleKey(), r.rate(), true);
            })
            .toList();
    }
}
