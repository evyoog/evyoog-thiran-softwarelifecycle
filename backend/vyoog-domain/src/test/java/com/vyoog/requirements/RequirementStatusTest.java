package com.vyoog.requirements;

import static com.vyoog.requirements.RequirementStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * VYB-0813 (D17): the transition table is covered exhaustively by tests. This test
 * enumerates every (from, to) pair — not just the legal ones — so a new status added
 * later without updating {@code allowedNext()} shows up as a failure here, not as a
 * surprise in production.
 */
class RequirementStatusTest {

    private static final Set<RequirementStatus> ALL = EnumSet.allOf(RequirementStatus.class);

    @ParameterizedTest
    @EnumSource(RequirementStatus.class)
    void everyOtherTransitionFromThisStateIsIllegal(RequirementStatus from) {
        for (RequirementStatus to : ALL) {
            boolean legal = from.allowedNext().contains(to);
            assertThat(from.canMoveTo(to))
                .as("%s -> %s should be %s".formatted(from, to, legal ? "legal" : "illegal"))
                .isEqualTo(legal);
        }
    }

    @Test
    void draftMovesOnlyToInReview() {
        assertThat(DRAFT.allowedNext()).containsExactly(IN_REVIEW);
    }

    @Test
    void draftToApprovedDirectlyIsRejected() {
        assertThat(DRAFT.canMoveTo(APPROVED)).isFalse();
    }

    @Test
    void inReviewMovesToDraftOrReviewedOnly() {
        // VYB-0813: rejecting directly from IN_REVIEW is gone — a decision is only
        // made once a review is actually complete.
        assertThat(IN_REVIEW.allowedNext()).containsExactlyInAnyOrder(DRAFT, REVIEWED);
    }

    @Test
    void inReviewToRejectedDirectlyIsRejected() {
        assertThat(IN_REVIEW.canMoveTo(REJECTED)).isFalse();
    }

    @Test
    void reviewedMovesToApprovedRejectedOrNeedsRevision() {
        assertThat(REVIEWED.allowedNext()).containsExactlyInAnyOrder(APPROVED, REJECTED, NEEDS_REVISION);
    }

    @Test
    void needsRevisionMovesOnlyToInReviewSkippingDraft() {
        assertThat(NEEDS_REVISION.allowedNext()).containsExactly(IN_REVIEW);
    }

    @Test
    void needsRevisionToDraftIsRejected() {
        assertThat(NEEDS_REVISION.canMoveTo(DRAFT)).isFalse();
    }

    @Test
    void approvedIsFullyTerminal() {
        assertThat(APPROVED.allowedNext()).isEmpty();
    }

    @Test
    void rejectedMovesOnlyToNeedsRevisionNotDraft() {
        // VYB-0813: REJECTED reopens into NEEDS_REVISION, not DRAFT — that changed
        // from the prior machine (D15/D16), which reopened straight to DRAFT.
        assertThat(REJECTED.allowedNext()).containsExactly(NEEDS_REVISION);
        assertThat(REJECTED.canMoveTo(DRAFT)).isFalse();
    }

    @Test
    void noStateCanMoveToItself() {
        for (RequirementStatus s : ALL) {
            assertThat(s.canMoveTo(s)).as("%s -> itself".formatted(s)).isFalse();
        }
    }

    @Test
    void everyValidTransitionInTheSpecTableIsReachable() {
        // The eight edges named in the state machine, asserted as one table so a
        // future change to allowedNext() that silently drops one shows up here.
        assertThat(DRAFT.canMoveTo(IN_REVIEW)).isTrue();
        assertThat(IN_REVIEW.canMoveTo(DRAFT)).isTrue();
        assertThat(IN_REVIEW.canMoveTo(REVIEWED)).isTrue();
        assertThat(REVIEWED.canMoveTo(APPROVED)).isTrue();
        assertThat(REVIEWED.canMoveTo(REJECTED)).isTrue();
        assertThat(REVIEWED.canMoveTo(NEEDS_REVISION)).isTrue();
        assertThat(NEEDS_REVISION.canMoveTo(IN_REVIEW)).isTrue();
        assertThat(REJECTED.canMoveTo(NEEDS_REVISION)).isTrue();
    }
}
