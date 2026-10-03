package com.vyoog.api.web;

import com.vyoog.api.config.RequiresAccess;
import com.vyoog.identity.AccessRule;
import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.detection.DetectionSweepService;
import com.vyoog.detection.Finding;
import com.vyoog.detection.FindingRepository;
import com.vyoog.detection.FindingService;
import com.vyoog.detection.FindingState;
import com.vyoog.identity.UserProvisioningService;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0153/0162/0163/0164 API surface: query findings, act on them, trigger a sweep. */
@RestController
@RequestMapping("/api/v1/findings")
public class FindingController {

    private final FindingRepository findings;
    private final FindingService service;
    private final DetectionSweepService sweeps;
    private final UserProvisioningService provisioning;
    private final PrincipalGuard guard;

    public FindingController(FindingRepository findings, FindingService service,
                              DetectionSweepService sweeps, UserProvisioningService provisioning, PrincipalGuard guard) {
        this.findings = findings;
        this.service = service;
        this.sweeps = sweeps;
        this.provisioning = provisioning;
        this.guard = guard;
    }

    /**
     * VYB-0616/0651: {@code confidence}/{@code model} are null for the twelve
     * rule/graph findings and populated for the five AI-derived ones — never cleared
     * on dismissal (AC2), because nothing in {@link Finding#dismiss} touches them.
     */
    public record FindingView(
        String id, String ruleKey, String objectType, String objectId, String severity,
        String title, String detail, String suggestion, String state, String dismissReason,
        java.math.BigDecimal confidence, String model, String discriminator) {}

    public record DismissRequest(@NotBlank String reason) {}
    public record SweepSummary(
        String ruleKey, int opened, int refreshed, int reopened, int resolved, boolean unavailable) {}

    private static FindingView toView(Finding f) {
        return new FindingView(
            f.getId().toString(), f.getRuleKey(), f.getObjectType(), f.getObjectId().toString(),
            f.getSeverity(), f.getTitle(), f.getDetail(), f.getSuggestion(),
            f.getState().name(), f.getDismissReason(), f.getConfidence(), f.getModel(), f.getDiscriminator());
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    @GetMapping
    public Page<FindingView> list(
            @RequestParam(defaultValue = "OPEN") FindingState state,
            @RequestParam(required = false) String ruleKey,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Finding> found = ruleKey != null
            ? findings.findAllByStateAndRuleKey(state, ruleKey, pageable)
            : findings.findAllByState(state, pageable);
        return found.map(FindingController::toView);
    }

    @RequiresAccess(value = AccessRule.REVIEW, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping("/{id}/dismiss")
    public FindingView dismiss(@PathVariable UUID id, @RequestBody DismissRequest body,
                                @AuthenticationPrincipal Jwt jwt) {
        return toView(service.dismiss(id, body.reason(), currentUserId(jwt)));
    }

    @RequiresAccess(value = AccessRule.REVIEW, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping("/{id}/accept")
    public FindingView accept(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return toView(service.accept(id, currentUserId(jwt)));
    }

    @RequiresAccess(value = AccessRule.REVIEW, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping("/{id}/reopen")
    public FindingView reopen(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return toView(service.reopen(id, currentUserId(jwt)));
    }

    // Runs synchronously and returns the real counts — 200, not 202: nothing async here.
    // VYB-0790: a tenant-wide, potentially-expensive scan — any authenticated user
    // could previously trigger this repeatedly with zero guard (a real DoS-adjacent
    // risk, especially given VYB-0781's own finding that a sweep can run for minutes
    // under CPU load at scale).
    @PostMapping("/sweep")
    public List<SweepSummary> triggerSweep(@AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        return sweeps.triggerManualSweep().stream()
            .map(r -> new SweepSummary(r.ruleKey(), r.opened(), r.refreshed(), r.reopened(), r.resolved(), r.unavailable()))
            .toList();
    }
}
