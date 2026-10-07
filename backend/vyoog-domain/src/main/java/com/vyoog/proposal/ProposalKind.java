package com.vyoog.proposal;

import com.vyoog.identity.AccessRule;
import java.util.Set;

/**
 * VYB-0938: what an AI proposal is for. The rule is the one that would let a person make the same change by hand, so
 * deciding a proposal needs no role of its own: accepting a rewrite is editing the requirement, accepting a test case is
 * drafting one, accepting a brief elaboration is generating a brief.
 */
public enum ProposalKind {
    /** {@code payload}: {@code statement}, {@code changes[]}. Accepting edits the requirement, if the proposal has one. */
    REWRITE(AccessRule.CREATE_EDIT_REQ, Set.of("statement")),
    /** {@code payload}: {@code category}, {@code title}, {@code description}, {@code rationale}. Accepting drafts the test case. */
    TEST_CASE(AccessRule.VERIFY, Set.of("title", "description")),
    /** {@code payload}: {@code detail}. Accepting makes it eligible for the briefs of that requirement's current revision. */
    BRIEF_ELABORATION(AccessRule.CREATE_EDIT_REQ, Set.of("detail"));

    private final AccessRule rule;
    private final Set<String> editableKeys;

    ProposalKind(AccessRule rule, Set<String> editableKeys) {
        this.rule = rule;
        this.editableKeys = editableKeys;
    }

    public AccessRule rule() {
        return rule;
    }

    /** The payload fields a person may change before accepting. */
    public Set<String> editableKeys() {
        return editableKeys;
    }
}
