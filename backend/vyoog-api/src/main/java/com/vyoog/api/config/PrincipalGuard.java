package com.vyoog.api.config;

import com.vyoog.identity.AccessRole;
import com.vyoog.identity.GrantRequiredException;
import com.vyoog.identity.GrantResolver;
import com.vyoog.identity.ScopeType;
import com.vyoog.identity.ServiceAccountChecker;
import com.vyoog.identity.ServiceAccountRefusedException;
import com.vyoog.identity.ServiceAccountRequiredException;
import com.vyoog.identity.StepUpChecker;
import com.vyoog.identity.UserProvisioningService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Where the raw {@link Jwt} claims this codebase's domain layer deliberately never
 * sees (see {@link ServiceAccountChecker}, {@link StepUpChecker}) meet the pure logic
 * that decides what they mean — the one place a controller needs to ask "is this
 * caller allowed to do this", for the checks VYB-0303/0305/0311/0701 all share.
 *
 * <p><strong>Only wired into this phase's new Administration endpoints</strong> — see
 * BUILD-REGISTER.md. Retrofitting a role check onto the ~300 endpoints Phases 1-4
 * already shipped is a real gap this session doesn't close; every one of them still
 * only requires "authenticated", not "authenticated and holding a specific role."
 */
@Component
public class PrincipalGuard {

    private final ServiceAccountChecker serviceAccounts;
    private final StepUpChecker stepUp;
    private final UserProvisioningService provisioning;
    private final GrantResolver grantResolver;

    @Value("${vyoog.stepup.required-level:step-up}")
    private String requiredStepUpLevel;

    public PrincipalGuard(ServiceAccountChecker serviceAccounts, StepUpChecker stepUp,
                           UserProvisioningService provisioning, GrantResolver grantResolver) {
        this.serviceAccounts = serviceAccounts;
        this.stepUp = stepUp;
        this.provisioning = provisioning;
        this.grantResolver = grantResolver;
    }

    /**
     * VYB-0305: approval, sign-off and administration are refused to a service account.
     * VYB-0901: and to any token that is neither a registered service account nor a person
     * (no {@code email} claim) — such a token has no user to attribute the action to.
     */
    public void requireHuman(Jwt jwt) {
        if (!serviceAccounts.isPerson(jwt.getClaimAsString("azp"), jwt.getClaimAsString("email"))) {
            throw new ServiceAccountRefusedException("A service account cannot do this — sign in as a person");
        }
    }

    /** VYB-0311: CI ingestion accepts only a registered service account (VYB-0901: never just "no email"). */
    public void requireServiceAccount(Jwt jwt) {
        if (!serviceAccounts.isServiceAccount(jwt.getClaimAsString("azp"))) {
            throw new ServiceAccountRequiredException("This endpoint is for CI/service-account callers only");
        }
    }

    /** VYB-0311 with a scope: CI ingestion accepts only a service account holding the named scope. */
    public void requireServiceAccountScope(Jwt jwt, String scope) {
        requireServiceAccount(jwt);
        if (!serviceAccounts.hasScope(jwt.getClaimAsString("azp"), scope)) {
            throw new ServiceAccountRequiredException("This service account lacks the '" + scope + "' scope");
        }
    }

    /** VYB-0303: signing needs this deployment's configured step-up level, checked right now. */
    public void requireStepUp(Jwt jwt) {
        stepUp.assertAchieved(jwt.getClaimAsString("acr"), requiredStepUpLevel);
    }

    public String achievedAcr(Jwt jwt) {
        return jwt.getClaimAsString("acr");
    }

    private UUID resolveUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    /** VYB-0701/0703: refused naming exactly which role and scope was missing, resolved live on every call. */
    public void requireRole(Jwt jwt, AccessRole role, ScopeType scopeType, UUID scopeId) {
        UUID userId = resolveUserId(jwt);
        if (!grantResolver.hasEffectiveRole(userId, role, scopeType, scopeId)) {
            throw new GrantRequiredException(
                "This action needs %s at %s%s".formatted(role, scopeType, scopeId == null ? "" : " " + scopeId),
                role);
        }
    }

    /** The common case: platform-wide ADMINISTRATOR. */
    public void requireAdministrator(Jwt jwt) {
        requireRole(jwt, AccessRole.ADMINISTRATOR, ScopeType.PLATFORM, null);
    }
}
