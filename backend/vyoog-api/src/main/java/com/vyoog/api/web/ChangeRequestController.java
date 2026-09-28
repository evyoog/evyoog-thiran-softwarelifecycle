package com.vyoog.api.web;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.changerequest.ChangeRequest;
import com.vyoog.changerequest.ChangeRequestRepository;
import com.vyoog.changerequest.ChangeRequestService;
import com.vyoog.identity.UserProvisioningService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0390–0393. */
@RestController
@RequestMapping("/api/v1/change-requests")
public class ChangeRequestController {

    private final ChangeRequestRepository changeRequests;
    private final ChangeRequestService service;
    private final UserProvisioningService provisioning;
    private final PrincipalGuard guard;

    public ChangeRequestController(ChangeRequestRepository changeRequests, ChangeRequestService service,
                                    UserProvisioningService provisioning, PrincipalGuard guard) {
        this.changeRequests = changeRequests;
        this.service = service;
        this.provisioning = provisioning;
        this.guard = guard;
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    public record RaiseChangeRequest(
        @NotBlank String title, @NotBlank String rationale, @NotEmpty List<String> requirementIds) {}
    public record DecideRequest(boolean approve) {}
    public record ChangeRequestView(
        String id, String key, String title, String rationale, String raisedBy, String state,
        int impactRequirements, int impactApps, List<String> scope) {}
    public record ImpactView(int requirements, int tests, int applications, int briefs) {}

    private ChangeRequestView toView(ChangeRequest cr) {
        return new ChangeRequestView(
            cr.getId().toString(), cr.getKey(), cr.getTitle(), cr.getRationale(),
            cr.getRaisedBy() == null ? null : cr.getRaisedBy().toString(), cr.getState().name(),
            cr.getImpactRequirements(), cr.getImpactApps(),
            service.scope(cr.getId()).stream().map(UUID::toString).toList());
    }

    @GetMapping
    public List<ChangeRequestView> list() {
        return changeRequests.findAll().stream().map(this::toView).toList();
    }

    @GetMapping("/{id}")
    public ChangeRequestView get(@PathVariable UUID id) {
        return toView(changeRequests.findById(id).orElseThrow(NoSuchElementException::new));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChangeRequestView raise(@RequestBody RaiseChangeRequest body, @AuthenticationPrincipal Jwt jwt) {
        List<UUID> ids = body.requirementIds().stream().map(UUID::fromString).toList();
        return toView(service.raise(body.title(), body.rationale(), ids, currentUserId(jwt)));
    }

    /** VYB-0391/0393 AC1: called before the approve action, so impact is on screen first. */
    @PostMapping("/{id}/impact")
    public ImpactView impact(@PathVariable UUID id) {
        var summary = service.computeImpact(id);
        return new ImpactView(summary.requirements(), summary.tests(), summary.applications(), summary.briefs());
    }

    /**
     * VYB-0790: {@code requireHuman} alone let any authenticated human — not just an
     * approver — approve or reject a change request touching an arbitrary set of
     * requirements. `APPROVER` in {@code access_grant} isn't reused here on purpose:
     * per {@code RoleCapabilityRegistry}, that role name is already tied to a
     * different, review-participant-scoped mechanism (`review_participant.role`) —
     * reusing it for a platform-wide grant here would give the same name two
     * unrelated meanings. `ADMINISTRATOR` is the one role this codebase already uses
     * consistently for every other cross-cutting, high-impact action.
     */
    @PostMapping("/{id}/decide")
    public ChangeRequestView decide(@PathVariable UUID id, @RequestBody DecideRequest body,
                                     @AuthenticationPrincipal Jwt jwt) {
        guard.requireHuman(jwt);
        guard.requireAdministrator(jwt);
        return toView(service.decide(id, body.approve(), currentUserId(jwt)));
    }

    /**
     * VYB-0392: applying is "every scoped requirement has actually been edited
     * through {@code PATCH /requirements/{id}?changeRequestId=}" — this just records
     * that as done once the caller (the frontend, after every edit succeeded) says
     * so; it doesn't itself verify each one, since it has no staged content to apply.
     */
    @PostMapping("/{id}/apply")
    public ChangeRequestView apply(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        guard.requireHuman(jwt);
        guard.requireAdministrator(jwt);
        return toView(service.markAppliedIfComplete(id, currentUserId(jwt)));
    }
}
