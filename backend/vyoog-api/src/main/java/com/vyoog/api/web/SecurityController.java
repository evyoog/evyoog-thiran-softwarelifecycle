package com.vyoog.api.web;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.detection.Finding;
import com.vyoog.detection.FindingRepository;
import com.vyoog.detection.FindingState;
import com.vyoog.identity.AccessGrant;
import com.vyoog.identity.AccessGrantService;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * VYB-0754: separation-of-duties findings, departed-but-active accounts, stale keys
 * and expiring external grants — the first three are just OPEN findings for three
 * rule keys (VYB-0704/0705/0713 already reconcile them through the same detector
 * lifecycle as every other gap); the fourth isn't a detector finding at all (see
 * {@link AccessGrantService#expiringExternalGrants}), so it's read live here instead.
 */
@RestController
@RequestMapping("/api/v1/security")
public class SecurityController {

    private final FindingRepository findings;
    private final AccessGrantService grants;
    private final PrincipalGuard guard;

    public SecurityController(FindingRepository findings, AccessGrantService grants, PrincipalGuard guard) {
        this.findings = findings;
        this.grants = grants;
        this.guard = guard;
    }

    public record FindingSummary(String id, String title, String detail, String suggestion, String objectId) {}

    private static FindingSummary toSummary(Finding f) {
        return new FindingSummary(f.getId().toString(), f.getTitle(), f.getDetail(), f.getSuggestion(),
            f.getObjectId().toString());
    }

    public record ExpiringGrantView(String grantId, String userId, String role, String expiresAt) {}

    public record SecurityReport(
        List<FindingSummary> separationOfDuties, List<FindingSummary> departedAccounts,
        List<FindingSummary> staleKeys, List<ExpiringGrantView> expiringExternalGrants) {}

    /** VYB-0754 AC1: each item's own {@code suggestion} (findings) or a stated action (expiring grants) is the corrective action — nothing here is "informational only." */
    @GetMapping
    public SecurityReport report(@AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        List<Finding> sod = findings.findAllByStateAndRuleKey(FindingState.OPEN, "rbac-sod", Pageable.unpaged()).getContent();
        List<Finding> departed = findings.findAllByStateAndRuleKey(FindingState.OPEN, "departed-active", Pageable.unpaged()).getContent();
        List<Finding> stale = findings.findAllByStateAndRuleKey(FindingState.OPEN, "stale-key", Pageable.unpaged()).getContent();
        List<AccessGrant> expiring = grants.expiringExternalGrants(14);

        return new SecurityReport(
            sod.stream().map(SecurityController::toSummary).toList(),
            departed.stream().map(SecurityController::toSummary).toList(),
            stale.stream().map(SecurityController::toSummary).toList(),
            expiring.stream().map(g -> new ExpiringGrantView(
                g.getId().toString(), g.getUserId().toString(), g.getRole().name(), g.getExpiresAt().toString()))
                .toList());
    }
}
