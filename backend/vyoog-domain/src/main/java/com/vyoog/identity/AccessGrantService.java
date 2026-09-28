package com.vyoog.identity;

import com.vyoog.platform.audit.AuditService;
import com.vyoog.platform.config.AppConfigService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0701/0702/0703: grant creation and revocation. Resolution (does a grant apply
 * here) is {@link GrantResolver}'s job — this is only about a grant's own lifecycle.
 */
@Service
public class AccessGrantService {

    private final AccessGrantRepository grants;
    private final AppUserRepository users;
    private final AppConfigService config;
    private final AuditService audit;

    public AccessGrantService(AccessGrantRepository grants, AppUserRepository users,
                               AppConfigService config, AuditService audit) {
        this.grants = grants;
        this.users = users;
        this.config = config;
        this.audit = audit;
    }

    /**
     * VYB-0702 AC2: an EXTERNAL-status user's grant must expire — refused outright if
     * the caller didn't supply one. AC3: whatever they did supply is clamped to the
     * configured maximum, rather than accepted as asked and silently never enforced.
     */
    @Transactional
    public AccessGrant grant(UUID userId, AccessRole role, ScopeType scopeType, UUID scopeId,
                              Instant expiresAt, UUID grantedBy) {
        AppUser user = users.findById(userId).orElseThrow(() -> new NoSuchElementException("No such user"));
        if ("EXTERNAL".equals(user.getStatus())) {
            if (expiresAt == null) {
                throw new IllegalArgumentException("An external user's grant must have an expiry");
            }
            Instant maxAllowed = Instant.now().plus(config.maxExternalGrantDays(), ChronoUnit.DAYS);
            if (expiresAt.isAfter(maxAllowed)) {
                throw new IllegalArgumentException(
                    "An external grant may not exceed %d days".formatted(config.maxExternalGrantDays()));
            }
        }
        AccessGrant grant = grants.save(new AccessGrant(userId, role, scopeType, scopeId, grantedBy, expiresAt));
        audit.record(grantedBy, "grant.created", "ACCESS_GRANT", grant.getId(), null, Map.of(
            "userId", userId.toString(), "role", role.name(), "scopeType", scopeType.name(),
            "scopeId", String.valueOf(scopeId), "expiresAt", String.valueOf(expiresAt)));
        return grant;
    }

    /** VYB-0703: read live everywhere else — this just stamps {@code revoked_at}, nothing to invalidate elsewhere. */
    @Transactional
    public void revoke(UUID grantId, UUID actor) {
        AccessGrant grant = grants.findById(grantId).orElseThrow(NoSuchElementException::new);
        grant.revoke();
        grants.save(grant);
        audit.record(actor, "grant.revoked", "ACCESS_GRANT", grantId, null,
            Map.of("userId", grant.getUserId().toString(), "role", grant.getRole().name()));
    }

    public List<AccessGrant> forUser(UUID userId) {
        return grants.findAllByUserId(userId);
    }

    public List<AccessGrant> all() {
        return grants.findAll();
    }

    /**
     * VYB-0754: an external-user grant expiring within {@code withinDays} — reported
     * ahead of the deadline rather than only once it's already lapsed, which is what
     * makes it a security-screen item instead of just something {@link
     * AccessGrant#isActive} already answers.
     */
    public List<AccessGrant> expiringExternalGrants(int withinDays) {
        Instant cutoff = Instant.now().plus(withinDays, ChronoUnit.DAYS);
        return grants.findAll().stream()
            .filter(AccessGrant::isActive)
            .filter(g -> g.getExpiresAt() != null && g.getExpiresAt().isBefore(cutoff))
            .filter(g -> users.findById(g.getUserId()).map(u -> "EXTERNAL".equals(u.getStatus())).orElse(false))
            .toList();
    }
}
