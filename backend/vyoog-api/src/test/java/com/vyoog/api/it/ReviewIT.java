package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vyoog.clarification.ClarificationService;
import com.vyoog.identity.AccessRole;
import com.vyoog.review.Review;
import com.vyoog.review.ReviewParticipantInput;
import com.vyoog.review.ReviewParticipantRole;
import com.vyoog.review.ReviewService;
import com.vyoog.review.ReviewState;
import com.vyoog.requirements.Requirement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** VYB-0907 (F11): review rounds: frozen revisions, participants, signing, separation of duties, closing. */
class ReviewIT extends IntegrationTestBase {

    @Autowired ReviewService reviews;
    @Autowired ClarificationService clarifications;

    private Portfolio p;
    private UUID author, reviewer, approver, observer, admin;
    private Requirement r;

    @BeforeEach
    void fixtures() {
        p = newPortfolio();
        author = newUser("author");
        reviewer = newUser("reviewer");
        approver = newUser("approver");
        observer = newUser("observer");
        admin = newAdministrator();
        r = newRequirement(p, author);
    }

    private Review open(ReviewParticipantInput... participants) {
        return reviews.open(unique("Round"), "scope", List.of(r.getId()), List.of(participants), admin);
    }

    @Test
    void VYB0907_AC1_openingARoundFreezesEachItemsRevisionAndStoresTheParticipants() {
        Review round = open(new ReviewParticipantInput(reviewer, ReviewParticipantRole.REVIEWER),
            new ReviewParticipantInput(approver, ReviewParticipantRole.APPROVER));
        assertThat(jdbc.queryForObject("SELECT revision FROM review_item WHERE review_id = ? AND requirement_id = ?",
            Integer.class, round.getId(), r.getId())).isEqualTo(1);
        assertThat(reviews.participants(round.getId())).hasSize(2);
        assertThat(round.getState()).isEqualTo(ReviewState.OPEN);
    }

    @Test
    void VYB0907_AC1_anApproverWhoseGrantCoversTheRequirementJoinsTheRoundAutomatically() {
        UUID granted = grantOnCapability(newUser("granted"), AccessRole.APPROVER, p.capabilityId());
        Review round = open(new ReviewParticipantInput(reviewer, ReviewParticipantRole.REVIEWER));
        assertThat(reviews.participants(round.getId())).extracting(pv -> pv.userId()).contains(granted);
    }

    @Test
    void VYB0907_AC2_aRoundGoesStaleOnceAnItemsRequirementMovesOn() {
        Review round = open(new ReviewParticipantInput(approver, ReviewParticipantRole.APPROVER));
        assertThat(reviews.isStale(round.getId())).isFalse();
        requirementService.update(r.getId(), r.getRevision(), r.getTitle(), "The statement changed under the review.",
            r.getType(), r.getPriority(), r.getCapabilityId(), author);
        assertThat(reviews.isStale(round.getId())).isTrue();
    }

    @Test
    void VYB0907_AC3_aParticipantSignsOnceAndSigningAgainIsANoOp() {
        Review round = open(new ReviewParticipantInput(approver, ReviewParticipantRole.APPROVER));
        reviews.sign(round.getId(), approver, "step-up", approver);
        reviews.sign(round.getId(), approver, "step-up", approver);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_participant WHERE review_id = ? AND signed_at IS NOT NULL",
            Integer.class, round.getId())).isEqualTo(1);
        assertThat(auditCount(round.getId(), "review.signed")).isEqualTo(1);
    }

    @Test
    void VYB0907_AC3_anObserverCannotSignAndANonParticipantIsRefused() {
        Review round = open(new ReviewParticipantInput(observer, ReviewParticipantRole.OBSERVER));
        assertThatThrownBy(() -> reviews.sign(round.getId(), observer, "step-up", observer))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Observers cannot sign");
        assertThatThrownBy(() -> reviews.sign(round.getId(), newUser("outsider"), "step-up", admin))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Not a participant");
    }

    @Test
    void VYB0907_AC4_anApproverCannotApproveARequirementTheyAuthoredAndTheRefusalIsAudited() {
        Review round = open(new ReviewParticipantInput(author, ReviewParticipantRole.APPROVER));
        assertThatThrownBy(() -> reviews.sign(round.getId(), author, "step-up", author))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("you are its author");
        assertThat(auditCount(round.getId(), "review.approval_refused_separation_of_duties")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT signed_at FROM review_participant WHERE review_id = ? AND user_id = ?",
            Object.class, round.getId(), author)).isNull();
    }

    @Test
    void VYB0907_AC5_aRoundWithNoApproverCannotClose() {
        Review round = open(new ReviewParticipantInput(reviewer, ReviewParticipantRole.REVIEWER));
        assertThatThrownBy(() -> reviews.close(round.getId(), admin))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("no approver");
    }

    @Test
    void VYB0907_AC5_anOpenBlockingClarificationStopsTheRoundClosingUntilItIsAnswered() {
        Review round = open(new ReviewParticipantInput(approver, ReviewParticipantRole.APPROVER));
        var question = clarifications.raise(r.getId(), "Which regulator applies?", reviewer, true, reviewer);

        assertThatThrownBy(() -> reviews.close(round.getId(), admin))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("blocking clarification");

        clarifications.answer(question.getId(), "The Indian one.", reviewer); // only the assignee may answer
        assertThat(reviews.close(round.getId(), admin).getState()).isEqualTo(ReviewState.CLOSED);
        assertThat(auditCount(round.getId(), "review.closed")).isEqualTo(1);
    }

    @Test
    void VYB0907_AC6_commentsAreStoredAgainstTheRoundAndTheRequirement() {
        Review round = open(new ReviewParticipantInput(reviewer, ReviewParticipantRole.REVIEWER));
        reviews.comment(round.getId(), r.getId(), reviewer, "Please add an acceptance criterion.");
        assertThat(reviews.comments(round.getId())).extracting(c -> c.getBody()).containsExactly("Please add an acceptance criterion.");
    }
}
