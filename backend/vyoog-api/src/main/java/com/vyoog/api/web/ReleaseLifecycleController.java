package com.vyoog.api.web;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.api.config.RequiresAccess;
import com.vyoog.identity.AccessRule;
import com.vyoog.identity.UserProvisioningService;
import com.vyoog.release.ReleaseGate;
import com.vyoog.release.ReleaseGateConfigService;
import com.vyoog.release.ReleaseLifecycleService;
import com.vyoog.release.ReleaseState;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * VYB-0928: a release's state machine and its readiness gates. Moving a release is an Approver decision (matrix
 * "Baseline", the rule every other release write uses); the gate configuration is an administrator's.
 */
@RestController
@RequestMapping("/api/v1")
public class ReleaseLifecycleController {

    private final ReleaseLifecycleService lifecycle;
    private final ReleaseGateConfigService gateConfig;
    private final UserProvisioningService provisioning;
    private final PrincipalGuard guard;

    public ReleaseLifecycleController(ReleaseLifecycleService lifecycle, ReleaseGateConfigService gateConfig,
                                       UserProvisioningService provisioning, PrincipalGuard guard) {
        this.lifecycle = lifecycle;
        this.gateConfig = gateConfig;
        this.provisioning = provisioning;
        this.guard = guard;
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    public record GateResultView(String gate, boolean passed, String detail) {}

    public record OfferedView(String to, boolean needsReason, boolean signatureRequired, boolean ready, List<GateResultView> gates) {}

    public record TransitionRequest(@NotBlank String to, String reason, Boolean override) {}

    public record TransitionView(String id, String from, String to, String reason, boolean overridden,
                                  List<GateResultView> failedGates, String changedBy, String changedAt,
                                  String signatureAcr, String authTime) {}

    public record TransitionedView(String id, String name, String state) {}

    /** The moves available now, each with its enabled gates evaluated, so a screen can show what stands in the way. */
    @GetMapping("/releases/{id}/gates")
    public List<OfferedView> options(@PathVariable UUID id) {
        return lifecycle.options(id).stream().map(ReleaseLifecycleController::view).toList();
    }

    private static OfferedView view(ReleaseLifecycleService.Offered o) {
        return new OfferedView(o.to().name(), o.needsReason(), o.signatureRequired(), o.ready(),
            o.gates().stream().map(g -> new GateResultView(g.gate().name(), g.passed(), g.detail())).toList());
    }

    public record BlockedItemView(String requirementId, String key, String reason) {}

    public record CurrentReleaseView(String id, String name, String state, String targetDate, List<OfferedView> moves,
                                      List<BlockedItemView> blocked) {}

    /**
     * The release being prepared (OPEN or FROZEN, earliest target date first) with what stands in its way: each
     * available move's readiness gates, and the committed requirements that are blocked. 204 when none is being
     * prepared. This is what the Home screen's "blocking the release" panel reads.
     */
    @GetMapping("/releases/current")
    public org.springframework.http.ResponseEntity<CurrentReleaseView> current() {
        return lifecycle.current().map(c -> org.springframework.http.ResponseEntity.ok(new CurrentReleaseView(
                c.release().getId().toString(), c.release().getName(), c.release().getState().name(),
                c.release().getTargetDate() == null ? null : c.release().getTargetDate().toString(),
                c.moves().stream().map(ReleaseLifecycleController::view).toList(),
                c.blocked().stream().map(b -> new BlockedItemView(b.requirementId(), b.key(), b.reason())).toList())))
            .orElseGet(() -> org.springframework.http.ResponseEntity.noContent().build());
    }

    /**
     * Moves the release. A failing readiness gate refuses it (409 listing each); {@code override: true} with a reason
     * proceeds and records both. Reopening a frozen release needs a reason. Moving to FROZEN or RELEASED is a signature
     * event: it needs step-up authentication (401 naming the level required otherwise) and records the level achieved.
     */
    @RequiresAccess(value = AccessRule.BASELINE, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping("/releases/{id}/transition")
    public TransitionedView transition(@PathVariable UUID id, @RequestBody TransitionRequest body, @AuthenticationPrincipal Jwt jwt) {
        ReleaseState to;
        try {
            to = ReleaseState.valueOf(body.to());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown release state: " + body.to());
        }
        // VYB-0929: freezing and releasing are signature events (spec 4.5): a person, with step-up checked right now. The
        // domain never sees the token, so the level achieved and when the person authenticated are read here and passed on.
        ReleaseLifecycleService.Signature signature = null;
        if (to.requiresSignature()) {
            guard.requireHuman(jwt);
            guard.requireStepUp(jwt);
            Long authTime = jwt.getClaim("auth_time") instanceof Number n ? n.longValue() : null;
            signature = new ReleaseLifecycleService.Signature(jwt.getClaimAsString("acr"),
                authTime == null ? null : Instant.ofEpochSecond(authTime));
        }
        var r = lifecycle.transition(id, to, body.reason(), Boolean.TRUE.equals(body.override()), currentUserId(jwt), signature);
        return new TransitionedView(r.getId().toString(), r.getName(), r.getState().name());
    }

    @GetMapping("/releases/{id}/history")
    public List<TransitionView> history(@PathVariable UUID id) {
        return lifecycle.history(id).stream().map(t -> new TransitionView(t.id().toString(), t.from().name(), t.to().name(),
            t.reason(), t.overridden(),
            t.failedGates().stream().map(f -> new GateResultView(f.gate().name(), false, f.detail())).toList(),
            t.changedBy() == null ? null : t.changedBy().toString(), t.changedAt().toString(),
            t.signatureAcr(), t.authTime() == null ? null : t.authTime().toString())).toList();
    }

    // ------------------------------------------------------ gate configuration

    public record GateSettingView(String transition, String gate, boolean enabled, Integer threshold) {}

    public record UpdateGate(boolean enabled, Integer threshold) {}

    private static GateSettingView view(ReleaseGateConfigService.Setting s) {
        return new GateSettingView(s.transition().name(), s.gate().name(), s.enabled(), s.threshold());
    }

    @GetMapping("/release-gates")
    public List<GateSettingView> gates() {
        return gateConfig.list().stream().map(ReleaseLifecycleController::view).toList();
    }

    /** Platform administrators only: which gates guard which transition, and the verified-share threshold. */
    @PutMapping("/release-gates/{transition}/{gate}")
    public GateSettingView updateGate(@PathVariable String transition, @PathVariable String gate, @RequestBody UpdateGate body,
                                       @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        ReleaseGateConfigService.Transition t;
        ReleaseGate g;
        try {
            t = ReleaseGateConfigService.Transition.valueOf(transition);
            g = ReleaseGate.valueOf(gate);
        } catch (IllegalArgumentException e) {
            throw new java.util.NoSuchElementException("No such gate: " + transition + "/" + gate);
        }
        return view(gateConfig.update(t, g, body.enabled(), body.threshold(), currentUserId(jwt)));
    }
}
