package com.vyoog.identity;

import java.util.List;

/**
 * VYB-0906: the roles matrix (spec §4.4, docs/08-architecture/security) as data, so every write
 * endpoint can declare which column of the matrix it needs and the answer is one lookup, not a
 * list of roles retyped at each call site.
 *
 * <p>An ADMINISTRATOR passes every rule (the precedent in {@code RequirementTransitionAuthorizer},
 * VYB-0815). Service accounts and tokens with no email pass none of them: every rule starts by
 * requiring a person. The matrix has no column for several things (defects, releases, portfolio
 * structure, teams); the product owner chose to map those to the nearest column, recorded where each
 * endpoint is annotated and in the register's session log.
 */
public enum AccessRule {

    /** Any signed-in person: the endpoint only touches the caller's own state, or computes without storing. */
    PERSON("act as a signed-in person", List.of()),

    /** Matrix "Create req" / "Edit req": Business Analyst, Architect. */
    CREATE_EDIT_REQ("create or edit requirements", List.of(AccessRole.BUSINESS_ANALYST, AccessRole.ARCHITECT)),

    /** Matrix "Review": Reviewer, Approver, Compliance Lead, Architect. */
    REVIEW("review", List.of(AccessRole.REVIEWER, AccessRole.APPROVER, AccessRole.COMPLIANCE_LEAD, AccessRole.ARCHITECT)),

    /** Matrix "Approve": Approver / Product Owner. */
    APPROVE("approve", List.of(AccessRole.APPROVER)),

    /** Matrix "Verify": QA / Tester. */
    VERIFY("verify", List.of(AccessRole.TESTER)),

    /** Matrix "Baseline": Approver / Product Owner. */
    BASELINE("baseline or release", List.of(AccessRole.APPROVER)),

    /** Matrix "Admin": platform Administrator only. */
    ADMIN("administer", List.of());

    private final String description;
    private final List<AccessRole> roles;

    AccessRule(String description, List<AccessRole> roles) {
        this.description = description;
        this.roles = roles;
    }

    public String description() { return description; }

    /** The roles that satisfy this rule on their own; an ADMINISTRATOR always does as well. */
    public List<AccessRole> roles() { return roles; }
}
