package com.vyoog.identity.detectors;

import com.vyoog.detection.Candidate;
import com.vyoog.detection.Detector;
import com.vyoog.identity.AccessGrant;
import com.vyoog.identity.AccessGrantRepository;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.GrantResolver;
import com.vyoog.identity.ScopeRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * VYB-0704 — "rbac-sod": a user holding both an authoring role (BUSINESS_ANALYST or
 * ARCHITECT — the two roles in {@link AccessRole} that actually write requirement
 * content) and APPROVER, or ADMINISTRATOR and APPROVER, at overlapping scope. This is
 * the grant model's own policy report — distinct from {@link
 * com.vyoog.review.SeparationOfDutiesDetector} ("sod"), which catches an actual
 * owner/author signing as approver on one specific review round. A user can hold
 * conflicting grants for months before ever chairing a round; this finds that without
 * waiting for someone to sign anything.
 *
 * <p>"Same scope" means overlapping, not identical — a PRODUCT-level APPROVER grant
 * conflicts with a CAPABILITY-level BUSINESS_ANALYST grant underneath that product,
 * because VYB-0701 AC1 means the PRODUCT grant already reaches that capability.
 */
@Component
public class GrantSeparationOfDutiesDetector implements Detector {

    private static final Set<AccessRole> AUTHORING = Set.of(AccessRole.BUSINESS_ANALYST, AccessRole.ARCHITECT);

    private final AccessGrantRepository grants;
    private final GrantResolver resolver;

    public GrantSeparationOfDutiesDetector(AccessGrantRepository grants, GrantResolver resolver) {
        this.grants = grants;
        this.resolver = resolver;
    }

    @Override
    public String ruleKey() {
        return "rbac-sod";
    }

    private static boolean conflicts(AccessRole a, AccessRole b) {
        return (AUTHORING.contains(a) && b == AccessRole.APPROVER)
            || (a == AccessRole.ADMINISTRATOR && b == AccessRole.APPROVER);
    }

    private boolean scopesOverlap(AccessGrant a, AccessGrant b) {
        ScopeRef aRef = new ScopeRef(a.getScopeType(), a.getScopeId());
        ScopeRef bRef = new ScopeRef(b.getScopeType(), b.getScopeId());
        return resolver.ancestorChain(b.getScopeType(), b.getScopeId()).contains(aRef)
            || resolver.ancestorChain(a.getScopeType(), a.getScopeId()).contains(bRef);
    }

    private static String describe(AccessGrant g) {
        return "%s at %s%s".formatted(g.getRole(), g.getScopeType(),
            g.getScopeId() == null ? "" : " " + g.getScopeId().toString().substring(0, 8));
    }

    private Candidate candidate(UUID userId, AccessGrant a, AccessGrant b) {
        return new Candidate(ruleKey(), "APP_USER", userId, b.getId().toString(), 1, "crit",
            "Holds conflicting grants",
            "%s also holds %s".formatted(describe(a), describe(b)),
            "Revoke one of the two grants, or move one to a scope that no longer overlaps.");
    }

    private List<Candidate> detect(UUID onlyUserId) {
        List<AccessGrant> active = (onlyUserId != null ? grants.findAllByUserId(onlyUserId) : grants.findAll())
            .stream().filter(AccessGrant::isActive).toList();
        Map<UUID, List<AccessGrant>> byUser = active.stream().collect(Collectors.groupingBy(AccessGrant::getUserId));

        List<Candidate> out = new ArrayList<>();
        for (var entry : byUser.entrySet()) {
            List<AccessGrant> userGrants = entry.getValue();
            for (AccessGrant a : userGrants) {
                for (AccessGrant b : userGrants) {
                    if (a.getId().compareTo(b.getId()) >= 0) continue; // each pair once
                    boolean conflict = conflicts(a.getRole(), b.getRole()) || conflicts(b.getRole(), a.getRole());
                    if (conflict && scopesOverlap(a, b)) {
                        out.add(candidate(entry.getKey(), a, b));
                    }
                }
            }
        }
        return out;
    }

    @Override
    public List<Candidate> scan() {
        return detect(null);
    }

    @Override
    public List<Candidate> scanOne(UUID objectId) {
        return detect(objectId); // objectId is the user whose grants changed
    }
}
