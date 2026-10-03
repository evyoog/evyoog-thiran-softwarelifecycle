package com.vyoog.api.web;

import com.vyoog.api.config.RequiresAccess;
import com.vyoog.identity.AccessRule;
import com.vyoog.identity.UserProvisioningService;
import com.vyoog.platform.RateLimiter;
import com.vyoog.requirements.BulkEditService;
import com.vyoog.requirements.RequirementStatus;
import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0121/0122: bulk edit as one row-by-row, undoable action. */
@RestController
@RequestMapping("/api/v1/requirements/bulk-edit")
public class BulkEditController {

    /** VYB-0782: one deployment, one cooldown — same "per tenant" as the rest of this single-tenant codebase. */
    private static final Duration COOLDOWN = Duration.ofSeconds(5);

    private final BulkEditService service;
    private final UserProvisioningService provisioning;
    private final RateLimiter rateLimiter;

    public BulkEditController(BulkEditService service, UserProvisioningService provisioning, RateLimiter rateLimiter) {
        this.service = service;
        this.provisioning = provisioning;
        this.rateLimiter = rateLimiter;
    }

    /** {@code reason} carries the rejection / no-criteria override the single-transition endpoint already demands. */
    public record BulkEditRequest(
        @NotEmpty List<String> ids, RequirementStatus status, String priority, String type,
        boolean touchCapability, String capabilityId, String ownerId, String reason) {}

    public record RowOutcomeView(String id, boolean applied, String reason) {}
    public record BulkResultView(String batchId, List<RowOutcomeView> outcomes) {}

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    private static BulkResultView toView(BulkEditService.BulkResult r) {
        return new BulkResultView(r.batchId().toString(),
            r.outcomes().stream().map(o -> new RowOutcomeView(o.id().toString(), o.applied(), o.reason())).toList());
    }

    // VYB-0906: the ids are in the body.
    @RequiresAccess(value = AccessRule.CREATE_EDIT_REQ, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping
    public BulkResultView apply(@RequestBody BulkEditRequest body, @AuthenticationPrincipal Jwt jwt) {
        rateLimiter.requireNotLimited("bulk-edit:" + jwt.getSubject(), COOLDOWN);
        var changes = new BulkEditService.Changes(
            body.status(), body.priority(), body.type(), body.touchCapability(),
            body.capabilityId() == null ? null : UUID.fromString(body.capabilityId()),
            body.ownerId() == null ? null : UUID.fromString(body.ownerId()),
            body.reason());
        List<UUID> ids = body.ids().stream().map(UUID::fromString).toList();
        return toView(service.apply(ids, changes, currentUserId(jwt)));
    }

    // VYB-0906: the batch is in the path but spans requirements.
    @RequiresAccess(value = AccessRule.CREATE_EDIT_REQ, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping("/{batchId}/undo")
    public BulkResultView undo(@PathVariable UUID batchId, @AuthenticationPrincipal Jwt jwt) {
        return toView(service.undo(batchId, currentUserId(jwt)));
    }
}
