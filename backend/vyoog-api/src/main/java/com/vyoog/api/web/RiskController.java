package com.vyoog.api.web;

import com.vyoog.api.config.RequiresAccess;
import com.vyoog.identity.AccessRule;
import com.vyoog.identity.UserProvisioningService;
import com.vyoog.risk.RiskService;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * VYB-0949 (F41, D40): the risk register. Reading is open to any signed-in person; adding, editing, closing and linking are for
 * the Approver, Compliance Lead, Architect or Administrator ({@link AccessRule#RISK}).
 */
@RestController
@RequestMapping("/api/v1/risks")
public class RiskController {

    private final RiskService service;
    private final UserProvisioningService provisioning;

    public RiskController(RiskService service, UserProvisioningService provisioning) {
        this.service = service;
        this.provisioning = provisioning;
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    public record RiskRowView(String id, String key, String title, int likelihood, int impact, int rating, String band, String status,
                              String ownerId, String ownerName, int requirements, int releases, String createdAt, String updatedAt, String closedAt) {}
    public record RiskLinkedRequirementView(String id, String key, String title, String status) {}
    public record RiskLinkedReleaseView(String id, String name, String state) {}
    public record RiskDetailView(RiskRowView risk, String description, String mitigation, String statusReason, String createdByName,
                                 List<RiskLinkedRequirementView> requirementLinks, List<RiskLinkedReleaseView> releaseLinks) {}
    public record RiskBody(@NotBlank String title, String description, int likelihood, int impact, String ownerId, String mitigation) {}
    public record RiskStatusChange(@NotBlank String status, String reason) {}
    public record RiskCreated(String id) {}

    private static RiskRowView view(RiskService.RiskRow r) {
        return new RiskRowView(r.id().toString(), r.key(), r.title(), r.likelihood(), r.impact(), r.rating(), r.band(), r.status(),
            r.ownerId() == null ? null : r.ownerId().toString(), r.ownerName(), r.requirements(), r.releases(),
            r.createdAt() == null ? null : r.createdAt().toString(), r.updatedAt() == null ? null : r.updatedAt().toString(),
            r.closedAt() == null ? null : r.closedAt().toString());
    }

    private static RiskService.Fields fields(RiskBody b) {
        return new RiskService.Fields(b.title(), b.description(), b.likelihood(), b.impact(),
            b.ownerId() == null || b.ownerId().isBlank() ? null : UUID.fromString(b.ownerId()), b.mitigation());
    }

    /** Every risk, highest rating first, open before closed. */
    @GetMapping
    public List<RiskRowView> list() {
        return service.list().stream().map(RiskController::view).toList();
    }

    @GetMapping("/{id}")
    public RiskDetailView get(@PathVariable UUID id) {
        var d = service.detail(id);
        return new RiskDetailView(view(d.row()), d.description(), d.mitigation(), d.statusReason(), d.createdByName(),
            d.requirementLinks().stream().map(r -> new RiskLinkedRequirementView(r.id().toString(), r.key(), r.title(), r.status())).toList(),
            d.releaseLinks().stream().map(r -> new RiskLinkedReleaseView(r.id().toString(), r.name(), r.state())).toList());
    }

    @RequiresAccess(value = AccessRule.RISK, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RiskCreated create(@RequestBody @jakarta.validation.Valid RiskBody body, @AuthenticationPrincipal Jwt jwt) {
        return new RiskCreated(service.create(fields(body), currentUserId(jwt)).toString());
    }

    @RequiresAccess(value = AccessRule.RISK, scope = RequiresAccess.Scope.ANYWHERE)
    @PutMapping("/{id}")
    public RiskDetailView update(@PathVariable UUID id, @RequestBody @jakarta.validation.Valid RiskBody body, @AuthenticationPrincipal Jwt jwt) {
        service.update(id, fields(body), currentUserId(jwt));
        return get(id);
    }

    @RequiresAccess(value = AccessRule.RISK, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping("/{id}/status")
    public RiskDetailView changeStatus(@PathVariable UUID id, @RequestBody @jakarta.validation.Valid RiskStatusChange body,
                                       @AuthenticationPrincipal Jwt jwt) {
        service.changeStatus(id, body.status(), body.reason(), currentUserId(jwt));
        return get(id);
    }

    @RequiresAccess(value = AccessRule.RISK, scope = RequiresAccess.Scope.ANYWHERE)
    @PutMapping("/{id}/requirements/{requirementId}")
    public void linkRequirement(@PathVariable UUID id, @PathVariable UUID requirementId, @AuthenticationPrincipal Jwt jwt) {
        service.linkRequirement(id, requirementId, currentUserId(jwt));
    }

    @RequiresAccess(value = AccessRule.RISK, scope = RequiresAccess.Scope.ANYWHERE)
    @DeleteMapping("/{id}/requirements/{requirementId}")
    public void unlinkRequirement(@PathVariable UUID id, @PathVariable UUID requirementId, @AuthenticationPrincipal Jwt jwt) {
        service.unlinkRequirement(id, requirementId, currentUserId(jwt));
    }

    @RequiresAccess(value = AccessRule.RISK, scope = RequiresAccess.Scope.ANYWHERE)
    @PutMapping("/{id}/releases/{releaseId}")
    public void linkRelease(@PathVariable UUID id, @PathVariable UUID releaseId, @AuthenticationPrincipal Jwt jwt) {
        service.linkRelease(id, releaseId, currentUserId(jwt));
    }

    @RequiresAccess(value = AccessRule.RISK, scope = RequiresAccess.Scope.ANYWHERE)
    @DeleteMapping("/{id}/releases/{releaseId}")
    public void unlinkRelease(@PathVariable UUID id, @PathVariable UUID releaseId, @AuthenticationPrincipal Jwt jwt) {
        service.unlinkRelease(id, releaseId, currentUserId(jwt));
    }
}
