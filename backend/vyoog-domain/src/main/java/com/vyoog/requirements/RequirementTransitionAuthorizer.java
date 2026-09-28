package com.vyoog.requirements;

import com.vyoog.identity.AccessRole;
import com.vyoog.identity.GrantRequiredException;
import com.vyoog.identity.GrantResolver;
import com.vyoog.identity.ScopeType;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * VYB-0813 (D17): who may make each of the eight edges in {@link RequirementStatus}'s
 * transition table, in one place — shared by {@link RequirementService#transition}
 * and {@link BulkEditService}, so the two guards cannot drift the way the reason and
 * blocking-clarification guards briefly did before VYB-0810 closed that gap.
 *
 * <p>"Author" is an identity check (owner or creator), not a grant — there is no
 * {@link AccessRole} for it. "Decision maker" reuses APPROVER, the same role the roles
 * matrix already names for the REVIEWED -> APPROVED edge; deciding REVIEWED's outcome
 * is refused to the requirement's own author (SoD), the same principle already
 * enforced when signing a review round (VYB-0304), now also enforced on the move
 * itself.
 */
@Service
public class RequirementTransitionAuthorizer {

    private final GrantResolver grantResolver;

    public RequirementTransitionAuthorizer(GrantResolver grantResolver) {
        this.grantResolver = grantResolver;
    }

    public void authorize(Requirement r, RequirementStatus from, RequirementStatus target, UUID actorId) {
        boolean isAuthor = actorId.equals(r.getOwnerId()) || actorId.equals(r.getCreatedBy());
        if (from == RequirementStatus.DRAFT && target == RequirementStatus.IN_REVIEW) {
            requireAuthor(r, isAuthor, "submit this for review");
        } else if (from == RequirementStatus.IN_REVIEW && target == RequirementStatus.DRAFT) {
            requireAuthor(r, isAuthor, "withdraw this back to draft");
        } else if (from == RequirementStatus.NEEDS_REVISION && target == RequirementStatus.IN_REVIEW) {
            requireAuthor(r, isAuthor, "resubmit this for review");
        } else if (from == RequirementStatus.IN_REVIEW && target == RequirementStatus.REVIEWED) {
            requireRoleOrAdmin(r, actorId, AccessRole.REVIEWER, "mark this reviewed");
        } else if (from == RequirementStatus.REVIEWED
                && (target == RequirementStatus.APPROVED || target == RequirementStatus.REJECTED
                    || target == RequirementStatus.NEEDS_REVISION)) {
            requireDecisionMaker(r, actorId, isAuthor, "record a decision on this review");
        } else if (from == RequirementStatus.REJECTED && target == RequirementStatus.NEEDS_REVISION) {
            requireRoleOrAdmin(r, actorId, AccessRole.APPROVER, "reopen this");
        }
        // Any other pair either isn't reachable (allowedNext refuses it first) or
        // carries no actor restriction of its own.
    }

    private void requireAuthor(Requirement r, boolean isAuthor, String action) {
        if (!isAuthor) {
            throw new IllegalStateException("Only " + r.getKey() + "'s author may " + action);
        }
    }

    private void requireDecisionMaker(Requirement r, UUID actorId, boolean isAuthor, String action) {
        // VYB-0815: a platform ADMINISTRATOR is exempt from the author-exclusion (SoD)
        // rule and the APPROVER requirement below — the same "admin is the one role that
        // is never blocked by the role machinery" principle requireRoleOrAdmin already
        // applies elsewhere in this class. Everyone else still faces both checks.
        if (grantResolver.hasEffectiveRole(actorId, AccessRole.ADMINISTRATOR, ScopeType.PLATFORM, null)) {
            return;
        }
        if (isAuthor) {
            throw new IllegalStateException(
                "You cannot " + action + " for " + r.getKey() + " — you are its author");
        }
        requireRole(r, actorId, AccessRole.APPROVER, action);
    }

    private void requireRole(Requirement r, UUID actorId, AccessRole role, String action) {
        ScopeRef scope = scopeOf(r);
        if (!grantResolver.hasEffectiveRole(actorId, role, scope.type(), scope.id())) {
            throw new GrantRequiredException(
                "This action needs " + role + " to " + action + " on " + r.getKey(), role);
        }
    }

    private void requireRoleOrAdmin(Requirement r, UUID actorId, AccessRole role, String action) {
        ScopeRef scope = scopeOf(r);
        boolean hasRole = grantResolver.hasEffectiveRole(actorId, role, scope.type(), scope.id())
            || grantResolver.hasEffectiveRole(actorId, AccessRole.ADMINISTRATOR, ScopeType.PLATFORM, null);
        if (!hasRole) {
            throw new GrantRequiredException(
                "This action needs " + role + " or ADMINISTRATOR to " + action + " on " + r.getKey(), role);
        }
    }

    private record ScopeRef(ScopeType type, UUID id) {}

    private ScopeRef scopeOf(Requirement r) {
        if (r.getCapabilityId() != null) return new ScopeRef(ScopeType.CAPABILITY, r.getCapabilityId());
        if (r.getApplicationId() != null) return new ScopeRef(ScopeType.APP, r.getApplicationId());
        if (r.getProductId() != null) return new ScopeRef(ScopeType.PRODUCT, r.getProductId());
        return new ScopeRef(ScopeType.PLATFORM, null);
    }
}
