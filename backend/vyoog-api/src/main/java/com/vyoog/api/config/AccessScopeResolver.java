package com.vyoog.api.config;

import com.vyoog.identity.ScopeType;
import com.vyoog.requirements.AcceptanceCriterionRepository;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** VYB-0906: turns the resource id in a URL into the grant scope a role is checked at. A missing resource is a 404, not an open door. */
@Component
public class AccessScopeResolver {

    public record Resolved(ScopeType type, UUID id) {}

    private final RequirementRepository requirements;
    private final AcceptanceCriterionRepository criteria;

    public AccessScopeResolver(RequirementRepository requirements, AcceptanceCriterionRepository criteria) {
        this.requirements = requirements;
        this.criteria = criteria;
    }

    public Resolved ofRequirement(UUID requirementId) {
        Requirement r = requirements.findById(requirementId).orElseThrow(NoSuchElementException::new);
        return ofPlacement(r.getCapabilityId(), r.getApplicationId(), r.getProductId());
    }

    public Resolved ofCriterion(UUID criterionId) {
        var c = criteria.findById(criterionId).orElseThrow(NoSuchElementException::new);
        return ofRequirement(c.getRequirementId());
    }

    /** Narrowest placement wins: capability, else application, else product, else platform. */
    public static Resolved ofPlacement(UUID capabilityId, UUID applicationId, UUID productId) {
        if (capabilityId != null) return new Resolved(ScopeType.CAPABILITY, capabilityId);
        if (applicationId != null) return new Resolved(ScopeType.APP, applicationId);
        if (productId != null) return new Resolved(ScopeType.PRODUCT, productId);
        return new Resolved(ScopeType.PLATFORM, null);
    }
}
