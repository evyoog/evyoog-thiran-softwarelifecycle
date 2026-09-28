package com.vyoog.api.web;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.identity.KnownServiceScopes;
import com.vyoog.identity.ServiceAccount;
import com.vyoog.identity.ServiceAccountService;
import com.vyoog.identity.UserProvisioningService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0710–0713/0753. */
@RestController
@RequestMapping("/api/v1/service-accounts")
public class ServiceAccountController {

    private final ServiceAccountService accounts;
    private final UserProvisioningService provisioning;
    private final PrincipalGuard guard;

    public ServiceAccountController(ServiceAccountService accounts, UserProvisioningService provisioning,
                                     PrincipalGuard guard) {
        this.accounts = accounts;
        this.provisioning = provisioning;
        this.guard = guard;
    }

    /** VYB-0710 AC2/VYB-0753 AC1: {@code everUsed} distinguishes "never used" from "used long ago" (both otherwise look like "no recent activity"); {@code keyAgeDays} is what a stale-key flag on the frontend reads. */
    public record ServiceAccountView(
        String id, String name, String purpose, String clientId, List<String> scopes,
        String keyIssuedAt, long keyAgeDays, boolean everUsed, String lastUsedAt,
        String previousClientId, String keyRotationOverlapUntil, String rotatedAt) {}

    private static ServiceAccountView toView(ServiceAccount a) {
        long ageDays = java.time.Duration.between(a.getKeyIssuedAt(), java.time.Instant.now()).toDays();
        return new ServiceAccountView(a.getId().toString(), a.getName(), a.getPurpose(), a.getClientId(),
            a.getScopes(), a.getKeyIssuedAt().toString(), ageDays, a.everUsed(),
            a.getLastUsedAt() == null ? null : a.getLastUsedAt().toString(),
            a.getPreviousClientId(),
            a.getKeyRotationOverlapUntil() == null ? null : a.getKeyRotationOverlapUntil().toString(),
            a.getRotatedAt() == null ? null : a.getRotatedAt().toString());
    }

    @GetMapping
    public List<ServiceAccountView> all(@AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        return accounts.all().stream().map(ServiceAccountController::toView).toList();
    }

    @GetMapping("/known-scopes")
    public List<String> knownScopes(@AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        return KnownServiceScopes.ALL.stream().sorted().toList();
    }

    public record CreateServiceAccount(
        @NotBlank String name, String purpose, @NotBlank String clientId, @NotEmpty List<String> scopes) {}

    @PostMapping
    public ServiceAccountView create(@RequestBody CreateServiceAccount body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        return toView(accounts.create(body.name(), body.purpose(), body.clientId(), body.scopes(), currentUserId(jwt)));
    }

    public record RotateKey(@NotBlank String newClientId) {}

    /** VYB-0712: records the rotation and starts its overlap window; see ServiceAccountService's Javadoc for what this does and doesn't reach in Keycloak itself. */
    @PostMapping("/{id}/rotate")
    public ServiceAccountView rotate(@PathVariable UUID id, @RequestBody RotateKey body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        return toView(accounts.rotate(id, body.newClientId(), currentUserId(jwt)));
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }
}
