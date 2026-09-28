package com.vyoog.api.web;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.identity.AccessGrant;
import com.vyoog.identity.AccessGrantService;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.RoleCapabilityRegistry;
import com.vyoog.identity.ScopeType;
import com.vyoog.identity.UserProvisioningService;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0701–0704/0751/0752/0758. */
@RestController
@RequestMapping("/api/v1/grants")
public class GrantController {

    private final AccessGrantService grants;
    private final UserProvisioningService provisioning;
    private final PrincipalGuard guard;

    public GrantController(AccessGrantService grants, UserProvisioningService provisioning, PrincipalGuard guard) {
        this.grants = grants;
        this.provisioning = provisioning;
        this.guard = guard;
    }

    public record GrantView(
        String id, String userId, AccessRole role, ScopeType scopeType, String scopeId,
        String grantedBy, String grantedAt, String expiresAt, String revokedAt, boolean active) {}

    private static GrantView toView(AccessGrant g) {
        return new GrantView(g.getId().toString(), g.getUserId().toString(), g.getRole(), g.getScopeType(),
            g.getScopeId() == null ? null : g.getScopeId().toString(),
            g.getGrantedBy() == null ? null : g.getGrantedBy().toString(),
            g.getGrantedAt().toString(), g.getExpiresAt() == null ? null : g.getExpiresAt().toString(),
            g.getRevokedAt() == null ? null : g.getRevokedAt().toString(), g.isActive());
    }

    @GetMapping
    public List<GrantView> all(@AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        return grants.all().stream().map(GrantController::toView).toList();
    }

    @GetMapping("/user/{userId}")
    public List<GrantView> forUser(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        return grants.forUser(userId).stream().map(GrantController::toView).toList();
    }

    public record CreateGrant(
        @NotNull UUID userId, @NotNull AccessRole role, @NotNull ScopeType scopeType, UUID scopeId, Instant expiresAt) {}

    /** VYB-0751 AC1: the scope picker on the frontend follows the hierarchy; this just validates what it sends. */
    @PostMapping
    public GrantView create(@RequestBody CreateGrant body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        AccessGrant g = grants.grant(body.userId(), body.role(), body.scopeType(), body.scopeId(),
            body.expiresAt(), currentUserId(jwt));
        return toView(g);
    }

    /** VYB-0758 AC1: the frontend confirms and names the user/scope before calling this — this is the action it confirms. */
    @PostMapping("/{id}/revoke")
    public void revoke(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        grants.revoke(id, currentUserId(jwt));
    }

    public record RoleCapabilityView(AccessRole role, String description, boolean enforced, String enforcedBy) {}

    /** VYB-0752: read straight off {@link RoleCapabilityRegistry} — nothing here is typed out twice. */
    @GetMapping("/roles-matrix")
    public List<RoleCapabilityView> rolesMatrix() {
        return RoleCapabilityRegistry.ALL.stream()
            .map(c -> new RoleCapabilityView(c.role(), c.description(), c.enforced(), c.enforcedBy()))
            .toList();
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }
}
