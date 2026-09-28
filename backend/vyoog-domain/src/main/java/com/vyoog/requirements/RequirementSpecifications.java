package com.vyoog.requirements;

import com.vyoog.trace.TraceLink;
import com.vyoog.trace.TraceLinkType;
import com.vyoog.trace.TraceObjectType;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * VYB-0130 AC1: filters compose with AND. Each method returns a fragment; the
 * controller composes only the fragments whose parameter was actually supplied, so an
 * unfiltered request stays a plain "not deleted" scan rather than a chain of
 * always-true predicates.
 */
public final class RequirementSpecifications {

    private RequirementSpecifications() {}

    public static Specification<Requirement> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<Requirement> hasStatus(RequirementStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Requirement> hasCapability(UUID capabilityId) {
        return (root, query, cb) -> cb.equal(root.get("capabilityId"), capabilityId);
    }

    public static Specification<Requirement> hasType(String type) {
        return (root, query, cb) -> cb.equal(root.get("type"), type);
    }

    public static Specification<Requirement> hasPriority(String priority) {
        return (root, query, cb) -> cb.equal(root.get("priority"), priority);
    }

    /**
     * VYB-0666: whether the requirement has any acceptance criteria at all.
     *
     * <p>A correlated EXISTS rather than a join: joining acceptance_criterion would
     * multiply the requirement row once per criterion, and the page size would then count
     * criteria instead of requirements — a requirement with six criteria would fill a
     * six-row page on its own.
     */
    public static Specification<Requirement> hasAcceptanceCriteria(boolean has) {
        return (root, query, cb) -> {
            var sub = query.subquery(Long.class);
            var criterion = sub.from(AcceptanceCriterion.class);
            sub.select(cb.literal(1L))
               .where(cb.equal(criterion.get("requirementId"), root.get("id")));
            return has ? cb.exists(sub) : cb.not(cb.exists(sub));
        };
    }

    /**
     * VYB-0831: whether at least one TEST --VERIFIES--> REQUIREMENT trace link points at
     * this requirement — the delivery brief's "only load requirements that already have
     * a test case" gate, expressed as a requirement-list filter rather than a separate
     * read path. A correlated EXISTS against {@code trace_link} directly, not a join
     * through {@code test_case}: the VERIFIES link's own {@code fromId} already is the
     * test case's id, so its mere existence is the proof — same reasoning as {@link
     * #hasAcceptanceCriteria}.
     */
    public static Specification<Requirement> hasTestCase(boolean has) {
        return (root, query, cb) -> {
            var sub = query.subquery(Long.class);
            var link = sub.from(TraceLink.class);
            sub.select(cb.literal(1L))
               .where(cb.equal(link.get("toId"), root.get("id")),
                      cb.equal(link.get("toType"), TraceObjectType.REQUIREMENT),
                      cb.equal(link.get("fromType"), TraceObjectType.TEST),
                      cb.equal(link.get("linkType"), TraceLinkType.VERIFIES));
            return has ? cb.exists(sub) : cb.not(cb.exists(sub));
        };
    }

    public static Specification<Requirement> hasOwner(UUID ownerId) {
        return (root, query, cb) -> cb.equal(root.get("ownerId"), ownerId);
    }

    /** VYB-0175 AC1: text columns filter by contains, case-insensitive. */
    public static Specification<Requirement> titleContains(String text) {
        return (root, query, cb) -> cb.like(cb.lower(root.get("title")), "%" + text.toLowerCase() + "%");
    }
}
