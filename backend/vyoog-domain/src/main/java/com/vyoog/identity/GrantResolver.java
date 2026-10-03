package com.vyoog.identity;

import com.vyoog.portfolio.ApplicationRepository;
import com.vyoog.portfolio.Capability;
import com.vyoog.portfolio.CapabilityRepository;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * VYB-0701: "a grant at a higher level implies the role at every level beneath" (AC1),
 * resolved by "walking upward from the target and stopping at the first match" (AC2).
 * The hierarchy is CAPABILITY → APP → PRODUCT → PLATFORM; RELEASE has no parent of its
 * own in this schema ({@link com.vyoog.release.Release} carries no product/app
 * reference) and so sits directly beneath PLATFORM.
 */
@Service
public class GrantResolver {

    private final AccessGrantRepository grants;
    private final CapabilityRepository capabilities;
    private final ApplicationRepository applications;

    public GrantResolver(AccessGrantRepository grants, CapabilityRepository capabilities,
                          ApplicationRepository applications) {
        this.grants = grants;
        this.capabilities = capabilities;
        this.applications = applications;
    }

    /** Narrowest first, PLATFORM always last. */
    public List<ScopeRef> ancestorChain(ScopeType type, UUID id) {
        List<ScopeRef> chain = new ArrayList<>();
        switch (type) {
            case PLATFORM -> { return List.of(ScopeRef.platform()); }
            case RELEASE -> chain.add(new ScopeRef(ScopeType.RELEASE, id));
            case PRODUCT -> chain.add(new ScopeRef(ScopeType.PRODUCT, id));
            case APP -> {
                chain.add(new ScopeRef(ScopeType.APP, id));
                UUID productId = applications.findById(id)
                    .orElseThrow(() -> new NoSuchElementException("no such application " + id))
                    .getProductId();
                chain.add(new ScopeRef(ScopeType.PRODUCT, productId));
            }
            case CAPABILITY -> {
                Capability cap = capabilities.findById(id)
                    .orElseThrow(() -> new NoSuchElementException("no such capability " + id));
                chain.add(new ScopeRef(ScopeType.CAPABILITY, id));
                chain.add(new ScopeRef(ScopeType.APP, cap.getApplicationId()));
                UUID productId = applications.findById(cap.getApplicationId())
                    .orElseThrow(() -> new NoSuchElementException("no such application " + cap.getApplicationId()))
                    .getProductId();
                chain.add(new ScopeRef(ScopeType.PRODUCT, productId));
            }
        }
        chain.add(ScopeRef.platform());
        return chain;
    }

    /**
     * VYB-0701 AC2: walks the chain narrow-to-wide and stops at the first active grant
     * of exactly this role. VYB-0703: reads {@code access_grant} live on every call —
     * there is no cache to invalidate, so a revocation a moment ago is already honoured.
     */
    public boolean hasEffectiveRole(UUID userId, AccessRole role, ScopeType targetType, UUID targetId) {
        List<AccessGrant> active = grants.findAllByUserId(userId).stream()
            .filter(AccessGrant::isActive)
            .filter(g -> g.getRole() == role)
            .toList();
        if (active.isEmpty()) return false;
        for (ScopeRef level : ancestorChain(targetType, targetId)) {
            for (AccessGrant g : active) {
                if (level.matches(g.getScopeType(), g.getScopeId())) return true;
            }
        }
        return false;
    }

    /**
     * VYB-0906: does the user hold this role at <em>any</em> scope. For endpoints whose target is not
     * in the URL (the placement or the ids are in the body), this is the gate in front of the
     * handler; the scoped check, where one matters, happens once the body is read.
     */
    public boolean holdsRoleAnywhere(UUID userId, AccessRole role) {
        return grants.findAllByUserId(userId).stream().anyMatch(g -> g.isActive() && g.getRole() == role);
    }

    /** Every role the user holds at or above the target scope, unioned across the whole chain. */
    public Set<AccessRole> effectiveRoles(UUID userId, ScopeType targetType, UUID targetId) {
        List<AccessGrant> active = grants.findAllByUserId(userId).stream().filter(AccessGrant::isActive).toList();
        List<ScopeRef> chain = ancestorChain(targetType, targetId);
        Set<AccessRole> roles = EnumSet.noneOf(AccessRole.class);
        for (AccessGrant g : active) {
            for (ScopeRef level : chain) {
                if (level.matches(g.getScopeType(), g.getScopeId())) {
                    roles.add(g.getRole());
                    break;
                }
            }
        }
        return roles;
    }

    /** Convenience for the common "is this person an administrator anywhere that matters" check. */
    public boolean isPlatformAdministrator(UUID userId) {
        return hasEffectiveRole(userId, AccessRole.ADMINISTRATOR, ScopeType.PLATFORM, null);
    }
}
