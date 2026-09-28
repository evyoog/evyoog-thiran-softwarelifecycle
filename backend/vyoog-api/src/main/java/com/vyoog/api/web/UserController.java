package com.vyoog.api.web;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.AppUser;
import com.vyoog.identity.AppUserService;
import com.vyoog.identity.ScopeType;
import com.vyoog.identity.UserProvisioningService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0700/0705/0706/0750. */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final AppUserService users;
    private final UserProvisioningService provisioning;
    private final PrincipalGuard guard;

    public UserController(AppUserService users, UserProvisioningService provisioning, PrincipalGuard guard) {
        this.users = users;
        this.provisioning = provisioning;
        this.guard = guard;
    }

    /**
     * VYB-0700 AC1/VYB-0750 AC1/AC2: source, status, last seen and grant count in one
     * row each — {@code departedButActive} is what VYB-0750 AC2 calls "flagged", read
     * straight off the same row rather than a second round trip per user.
     */
    public record UserView(
        String id, String email, String displayName, String source, String status,
        String statusChangedAt, boolean mfaEnrolled, String lastSeenAt, long activeGrantCount,
        boolean departedButActive, String delegateId, String managerId) {}

    private static UserView toView(AppUserService.DirectoryRow row) {
        AppUser u = row.user();
        boolean departedButActive = "DEPARTED".equals(u.getStatus()) && row.activeGrantCount() > 0;
        return new UserView(u.getId().toString(), u.getEmail(), u.getDisplayName(), u.getSource(), u.getStatus(),
            u.getStatusChangedAt().toString(), u.isMfaEnrolled(),
            u.getLastSeenAt() == null ? null : u.getLastSeenAt().toString(),
            row.activeGrantCount(), departedButActive,
            u.getDelegateId() == null ? null : u.getDelegateId().toString(),
            u.getManagerId() == null ? null : u.getManagerId().toString());
    }

    /**
     * {@code role} is optional — omit it to add someone to the directory with no access
     * at all. When present it is granted immediately; it is a Vyoog access grant, not a
     * Keycloak role, and nothing about it waits on the identity provider.
     */
    public record CreateUser(@NotBlank String email, @NotBlank String displayName,
                             AccessRole role, ScopeType scopeType, UUID scopeId) {}

    /**
     * VYB-0700: adds a colleague to the directory ahead of their first sign-in, so grants
     * and a manager can be prepared for them. This creates no credential and no identity
     * — Keycloak owns both — only the local mirror row, which that person's first
     * sign-in then claims.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@RequestBody @Valid CreateUser body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        AppUser created = users.createLocalWithRole(body.email(), body.displayName(),
            body.role(), body.scopeType(), body.scopeId(), currentUserId(jwt));
        return toView(users.directoryRow(created.getId()));
    }

    @GetMapping
    public List<UserView> directory(@AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        return users.directory().stream().map(UserController::toView).toList();
    }

    public record PickerView(String id, String displayName, String email) {}

    /**
     * VYB-0502: the real developer/owner/tester picker — deliberately not behind
     * {@link PrincipalGuard#requireAdministrator}, unlike {@link #directory}: any
     * authenticated user can look up a colleague to assign, the same as they could
     * already see that colleague's name elsewhere in the product.
     */
    @GetMapping("/picker")
    public List<PickerView> picker(@RequestParam(required = false) String q) {
        return users.picker(q).stream()
            .map(r -> new PickerView(r.id().toString(), r.displayName(), r.email()))
            .toList();
    }

    public record SetStatus(@NotBlank String status) {}

    @PatchMapping("/{id}/status")
    public UserView setStatus(@PathVariable UUID id, @RequestBody SetStatus body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        users.setStatus(id, body.status(), currentUserId(jwt));
        return toView(users.directoryRow(id));
    }

    public record SetDelegate(String delegateId) {}

    /** VYB-0706: a user sets their own delegate — no administrator grant required for this one. */
    @PutMapping("/{id}/delegate")
    public void setDelegate(@PathVariable UUID id, @RequestBody SetDelegate body, @AuthenticationPrincipal Jwt jwt) {
        UUID actor = currentUserId(jwt);
        if (!actor.equals(id)) {
            guard.requireAdministrator(jwt); // an administrator may set it on someone's behalf; nobody else may.
        }
        users.setDelegate(id, body.delegateId() == null ? null : UUID.fromString(body.delegateId()), actor);
    }

    public record SetManager(String managerId) {}

    /** VYB-0334/0792: administrator-only, unlike delegate — org structure isn't self-service. */
    @PutMapping("/{id}/manager")
    public void setManager(@PathVariable UUID id, @RequestBody SetManager body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        users.setManager(id, body.managerId() == null ? null : UUID.fromString(body.managerId()), currentUserId(jwt));
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }
}
