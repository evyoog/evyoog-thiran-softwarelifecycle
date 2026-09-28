package com.vyoog.api.web;

import com.vyoog.identity.AccessGrant;
import com.vyoog.identity.AccessGrantService;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.AppUser;
import com.vyoog.identity.GrantResolver;
import com.vyoog.identity.ScopeType;
import com.vyoog.identity.UserProvisioningService;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final UserProvisioningService provisioning;
    private final AccessGrantService grants;
    private final GrantResolver grantResolver;

    public MeController(UserProvisioningService provisioning, AccessGrantService grants, GrantResolver grantResolver) {
        this.provisioning = provisioning;
        this.grants = grants;
        this.grantResolver = grantResolver;
    }

    public record GrantView(String role, String scopeType, String scopeId) {}
    public record MeView(String id, String email, String displayName,
                          boolean platformAdministrator, List<GrantView> activeGrants) {}

    /**
     * VYB-0765 AC2: the frontend hides a nav/palette entry the caller cannot reach by
     * reading this — {@code platformAdministrator} is the one coarse signal Phase 5
     * itself needs (only the Administration module is actually gated, see {@link
     * com.vyoog.identity.RoleCapabilityRegistry}'s own disclosure); {@code
     * activeGrants} is the raw list for anything finer a screen wants to check itself.
     */
    @GetMapping
    public MeView me(@AuthenticationPrincipal Jwt jwt) {
        AppUser user = provisioning.upsert(
            jwt.getSubject(),
            jwt.getClaimAsString("email"),
            jwt.getClaimAsString("preferred_username"));

        List<GrantView> active = grants.forUser(user.getId()).stream()
            .filter(AccessGrant::isActive)
            .map(g -> new GrantView(g.getRole().name(), g.getScopeType().name(),
                g.getScopeId() == null ? null : g.getScopeId().toString()))
            .toList();

        return new MeView(user.getId().toString(), user.getEmail(), user.getDisplayName(),
            grantResolver.hasEffectiveRole(user.getId(), AccessRole.ADMINISTRATOR, ScopeType.PLATFORM, null),
            active);
    }
}
