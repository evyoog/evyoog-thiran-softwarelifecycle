package com.vyoog.api.web;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.detection.DetectorQualityService;
import com.vyoog.detection.FindingRepository;
import com.vyoog.detection.FindingState;
import com.vyoog.detection.GapRuleService;
import com.vyoog.detection.GapRuleTemplate;
import com.vyoog.detection.GapRuleTemplateRepository;
import com.vyoog.identity.UserProvisioningService;
import com.vyoog.platform.audit.AuditService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * VYB-0223/0610/0615/0617/0618/0652/0653: every detector, its technique, whether it's
 * enabled, its confidence threshold, and its measured dismissal rate — the false
 * positive rate this class's own predecessor said "isn't built" now is, computed by
 * {@link DetectorQualityService} from real dismissals, never a placeholder figure.
 * VYB-0652 AC2 ("changes are audited") was a disclosed gap as of Phase 4's own
 * BUILD-REGISTER entry — closed here, in the same session that built {@code
 * AuditService}'s clean write path, rather than left for later.
 */
@RestController
@RequestMapping("/api/v1/rules")
public class RuleController {

    private final GapRuleTemplateRepository templates;
    private final GapRuleService rules;
    private final DetectorQualityService quality;
    private final FindingRepository findings;
    private final UserProvisioningService provisioning;
    private final AuditService audit;
    private final PrincipalGuard guard;

    public RuleController(GapRuleTemplateRepository templates, GapRuleService rules,
                           DetectorQualityService quality, FindingRepository findings,
                           UserProvisioningService provisioning, AuditService audit, PrincipalGuard guard) {
        this.templates = templates;
        this.rules = rules;
        this.quality = quality;
        this.findings = findings;
        this.provisioning = provisioning;
        this.audit = audit;
        this.guard = guard;
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    public record RuleView(
        String key, String name, String technique, String severity, String description, short phase,
        boolean enabled, BigDecimal threshold, long totalFindings, BigDecimal dismissalRate) {}

    public record SetEnabled(boolean enabled) {}
    public record SetThreshold(BigDecimal threshold) {}

    private RuleView toView(GapRuleTemplate t, java.util.Map<String, DetectorQualityService.DismissalRate> rates) {
        var rate = rates.get(t.getKey());
        return new RuleView(t.getKey(), t.getName(), t.getTechnique(), t.getSeverity(), t.getDescription(),
            t.getPhase(), rules.isEnabled(t.getKey()), rules.getThreshold(t.getKey()),
            rate == null ? 0 : rate.totalEverRaised(), rate == null ? BigDecimal.ZERO : rate.rate());
    }

    @GetMapping
    public List<RuleView> list() {
        var rates = quality.dismissalRateByRule().stream()
            .collect(java.util.stream.Collectors.toMap(DetectorQualityService.DismissalRate::ruleKey, r -> r));
        return templates.findAll().stream().map(t -> toView(t, rates)).toList();
    }

    /** VYB-0790: this is the exact same class of action SettingsController's own mutations already require ADMINISTRATOR for — this endpoint just never actually enforced it despite its own Javadoc saying "an administrator adjusts." */
    @PatchMapping("/{key}")
    public RuleView setEnabled(@PathVariable String key, @RequestBody SetEnabled body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        rules.setEnabled(key, body.enabled());
        audit.record(currentUserId(jwt), "rule.enabled-changed", "GAP_RULE", null, null,
            Map.of("key", key, "enabled", body.enabled()));
        return list().stream().filter(r -> r.key().equals(key)).findFirst().orElseThrow();
    }

    /** VYB-0610 AC1/VYB-0615 AC2/VYB-0652: an administrator adjusts a rule's threshold. */
    @PutMapping("/{key}/threshold")
    public RuleView setThreshold(@PathVariable String key, @RequestBody SetThreshold body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        rules.setThreshold(key, body.threshold());
        audit.record(currentUserId(jwt), "rule.threshold-changed", "GAP_RULE", null, null,
            Map.of("key", key, "threshold", String.valueOf(body.threshold())));
        return list().stream().filter(r -> r.key().equals(key)).findFirst().orElseThrow();
    }

    /**
     * VYB-0652 AC1: "the effect on current findings is stated before applying" — a
     * dry preview of how many currently-OPEN findings for this rule sit below a
     * candidate threshold and would disappear (be resolved) if it were applied.
     */
    @GetMapping("/{key}/threshold-preview")
    public long previewThresholdEffect(@PathVariable String key, @RequestParam BigDecimal threshold) {
        return findings.findAllByStateAndRuleKey(FindingState.OPEN, key, org.springframework.data.domain.Pageable.unpaged())
            .stream()
            .filter(f -> f.getConfidence() != null && f.getConfidence().compareTo(threshold) < 0)
            .count();
    }

    public record DismissalReasonView(String reason, long count) {}

    /** VYB-0653 AC2: the most common reasons a human actually gave. */
    @GetMapping("/{key}/dismissal-reasons")
    public List<DismissalReasonView> dismissalReasons(@PathVariable String key) {
        return quality.topDismissalReasons(key, 5).stream()
            .map(r -> new DismissalReasonView(r.reason(), r.count())).toList();
    }

    public record NoisyDetectorView(String ruleKey, BigDecimal rate, boolean disabled) {}

    /**
     * VYB-0618: an explicit administrative action — see {@link
     * DetectorQualityService#applyNoisyDetectorDefaults} for why nothing calls this
     * automatically.
     */
    @PostMapping("/apply-noisy-defaults")
    public List<NoisyDetectorView> applyNoisyDefaults(@AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        List<DetectorQualityService.NoisyDetectorResult> results = quality.applyNoisyDetectorDefaults();
        audit.record(currentUserId(jwt), "rule.noisy-defaults-applied", "GAP_RULE", null, null,
            Map.of("disabled", results.stream().map(DetectorQualityService.NoisyDetectorResult::ruleKey).toList()));
        return results.stream().map(r -> new NoisyDetectorView(r.ruleKey(), r.rate(), r.disabled())).toList();
    }
}
