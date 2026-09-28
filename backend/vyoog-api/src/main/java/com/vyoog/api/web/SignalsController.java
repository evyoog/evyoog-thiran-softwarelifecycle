package com.vyoog.api.web;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.identity.UserProvisioningService;
import com.vyoog.signals.ImpactVolume;
import com.vyoog.signals.ScopeSignals;
import com.vyoog.signals.SignalsExportService;
import com.vyoog.signals.SignalsService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0460–0465. */
@RestController
@RequestMapping("/api/v1/signals")
public class SignalsController {

    private final SignalsService service;
    private final SignalsExportService export;
    private final PrincipalGuard guard;
    private final UserProvisioningService provisioning;

    public SignalsController(SignalsService service, SignalsExportService export, PrincipalGuard guard,
                              UserProvisioningService provisioning) {
        this.service = service;
        this.export = export;
        this.guard = guard;
        this.provisioning = provisioning;
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    public record SignalsView(
        long requirementCount, long acceptanceCriteriaCount, long dependencyDepth, long crossApplicationReach,
        long ambiguityLoad, long openGaps, double changeRate, double novelty, String computedAt,
        Map<String, String> queries) {}

    private static SignalsView toView(ScopeSignals s) {
        return new SignalsView(s.requirementCount(), s.acceptanceCriteriaCount(), s.dependencyDepth(),
            s.crossApplicationReach(), s.ambiguityLoad(), s.openGaps(), s.changeRate(), s.novelty(),
            s.computedAt().toString(), s.queries());
    }

    /** VYB-0465: names the scope and the moment computed — exportable to a delivery tool. */
    @GetMapping
    public SignalsView compute(@RequestParam(required = false) List<UUID> capabilityIds) {
        return toView(service.compute(capabilityIds == null ? List.of() : capabilityIds));
    }

    /** VYB-0462 AC1: computed across every application, not hardcoded. */
    @GetMapping("/median")
    public SignalsView median() {
        return toView(service.median());
    }

    public record ImpactView(
        long requirements, long tests, long testsBorrowed, long applications, long capabilities,
        long owners, long teams, long briefs) {}

    /**
     * VYB-0464/0508: the volume affected by changing one requirement — Vyoog stops
     * here, no effort figure ever. VYB-0507: {@code testsBorrowed} marks the part of
     * {@code tests} that's CI-ingested rather than authored in Vyoog — see
     * {@link ImpactVolume}'s own note.
     */
    @GetMapping("/impact/{requirementId}")
    public ImpactView impact(@PathVariable UUID requirementId) {
        ImpactVolume v = service.computeImpact(requirementId);
        return new ImpactView(v.requirements(), v.tests(), v.testsBorrowed(), v.applications(),
            v.capabilities(), v.owners(), v.teams(), v.briefs());
    }

    public record PushResultView(boolean success, int statusCode, String error) {}

    /**
     * VYB-0465/0505: a real POST, to a URL an administrator configured on the
     * "planning" connection, of exactly this scope's signals — see
     * {@link SignalsExportService}'s own note for why this needs ADMINISTRATOR (it's
     * sending internal data to an external system, the same sensitivity as tenant
     * export).
     */
    @PostMapping("/push")
    public PushResultView push(@RequestParam(required = false) List<UUID> capabilityIds,
                                @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        var result = export.pushToDeliveryTool(capabilityIds == null ? List.of() : capabilityIds, currentUserId(jwt));
        return new PushResultView(result.success(), result.statusCode(), result.error());
    }
}
