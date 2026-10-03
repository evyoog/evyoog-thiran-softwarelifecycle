package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vyoog.identity.AccessRole;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementStatus;
import com.vyoog.requirements.StaleRevisionException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** VYB-0907 (F11): the requirement register against a real database: keys, revisions, the state machine, soft delete. */
class RequirementIT extends IntegrationTestBase {

    @Autowired com.vyoog.requirements.RequirementRepository requirements;

    private Portfolio p;
    private UUID author;
    private UUID admin;

    @BeforeEach
    void fixtures() {
        p = newPortfolio();
        author = newUser("author");
        admin = newAdministrator();
    }

    private Requirement update(Requirement r, String statement) {
        return requirementService.update(r.getId(), r.getRevision(), r.getTitle(), statement, r.getType(),
            r.getPriority(), r.getCapabilityId(), author);
    }

    @Test
    void VYB0907_AC1_creatingARequirementAllocatesAKeyRecordsRevisionOneAndAnAuditEvent() {
        Requirement r = newRequirement(p, author);
        assertThat(r.getKey()).matches("VY-\\d+");
        assertThat(r.getRevision()).isEqualTo(1);
        assertThat(r.getStatus()).isEqualTo(RequirementStatus.DRAFT);
        assertThat(revisionRows(r.getId())).isEqualTo(1);
        assertThat(auditCount(r.getId(), "requirement.created")).isEqualTo(1);
    }

    @Test
    void VYB0907_AC1_everyRequirementGetsItsOwnKeyAndTheKeysNeverCollide() {
        var keys = new java.util.HashSet<String>();
        for (int i = 0; i < 12; i++) keys.add(newRequirement(p, author).getKey());
        assertThat(keys).hasSize(12);
    }

    @Test
    void VYB0907_AC2_savingWithNothingChangedMakesNoRevisionAndNoAuditNoise() {
        Requirement r = newRequirement(p, author);
        Requirement same = update(r, r.getStatement());
        assertThat(same.getRevision()).isEqualTo(1);
        assertThat(revisionRows(r.getId())).isEqualTo(1);
        assertThat(auditCount(r.getId(), "requirement.revised")).isZero();
    }

    @Test
    void VYB0907_AC2_aMaterialEditMakesExactlyOneNewImmutableRevision() {
        Requirement r = newRequirement(p, author);
        String original = r.getStatement();
        Requirement edited = update(r, "The system shall do something else entirely.");
        assertThat(edited.getRevision()).isEqualTo(2);
        assertThat(revisionRows(r.getId())).isEqualTo(2);
        assertThat(auditCount(r.getId(), "requirement.revised")).isEqualTo(1);
        // revision 1 still holds the original words
        assertThat(jdbc.queryForObject(
            "SELECT statement FROM requirement_revision WHERE requirement_id = ? AND revision = 1", String.class, r.getId()))
            .isEqualTo(original);
    }

    @Test
    void VYB0907_AC2_anEditFromAStaleRevisionIsRefusedNamingBothRevisions() {
        Requirement r = newRequirement(p, author);
        update(r, "First change.");                       // now at revision 2
        assertThatThrownBy(() -> requirementService.update(r.getId(), 1, r.getTitle(), "A change made from a stale copy.",
            r.getType(), r.getPriority(), r.getCapabilityId(), author))
            .isInstanceOf(StaleRevisionException.class);
        assertThat(revisionRows(r.getId())).isEqualTo(2); // the stale edit wrote nothing
    }

    @Test
    void VYB0907_AC3_aRequirementWalksTheWholeLifecycleAndEveryMoveIsAudited() {
        UUID reviewer = grantOnCapability(newUser("reviewer"), AccessRole.REVIEWER, p.capabilityId());
        Requirement r = newRequirement(p, author);

        r = requirementService.transition(r.getId(), r.getRevision(), RequirementStatus.IN_REVIEW,
            "submitted without criteria on purpose", author);
        r = requirementService.transition(r.getId(), r.getRevision(), RequirementStatus.REVIEWED, null, reviewer);
        r = requirementService.transition(r.getId(), r.getRevision(), RequirementStatus.APPROVED, null, admin);

        assertThat(r.getStatus()).isEqualTo(RequirementStatus.APPROVED);
        assertThat(auditCount(r.getId(), "requirement.transitioned")).isEqualTo(3);
    }

    @Test
    void VYB0907_AC3_aMoveTheStateMachineDoesNotAllowIsRefusedAndChangesNothing() {
        Requirement r = newRequirement(p, author);
        assertThatThrownBy(() -> requirementService.transition(r.getId(), r.getRevision(), RequirementStatus.APPROVED, null, admin))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Invalid transition");
        assertThat(jdbc.queryForObject("SELECT status FROM requirement WHERE id = ?", String.class, r.getId())).isEqualTo("DRAFT");
        assertThat(auditCount(r.getId(), "requirement.transitioned")).isZero();
    }

    @Test
    void VYB0907_AC3_onlyTheAuthorMaySubmitAndOnlyAReviewerOrAdminMayMarkItReviewed() {
        Requirement r = newRequirement(p, author);
        UUID stranger = newUser("stranger");
        assertThatThrownBy(() -> requirementService.transition(r.getId(), r.getRevision(), RequirementStatus.IN_REVIEW, "x", stranger))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("author");

        Requirement submitted = requirementService.transition(r.getId(), r.getRevision(), RequirementStatus.IN_REVIEW, "x", author);
        assertThatThrownBy(() -> requirementService.transition(submitted.getId(), submitted.getRevision(),
            RequirementStatus.REVIEWED, null, stranger))
            .isInstanceOf(RuntimeException.class).hasMessageContaining("REVIEWER");
    }

    @Test
    void VYB0907_AC3_aRejectionNeedsAReasonAndSubmittingWithoutCriteriaNeedsAnOverride() {
        Requirement r = newRequirement(p, author);
        assertThatThrownBy(() -> requirementService.transition(r.getId(), r.getRevision(), RequirementStatus.IN_REVIEW, null, author))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("acceptance criteria");

        Requirement inReview = requirementService.transition(r.getId(), r.getRevision(), RequirementStatus.IN_REVIEW, "override", author);
        Requirement reviewed = requirementService.transition(inReview.getId(), inReview.getRevision(), RequirementStatus.REVIEWED, null, admin);
        assertThatThrownBy(() -> requirementService.transition(reviewed.getId(), reviewed.getRevision(),
            RequirementStatus.REJECTED, "  ", admin))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");
    }

    @Test
    void VYB0907_AC4_anApprovedRequirementCannotBeEditedDirectly() {
        Requirement r = approved(newRequirement(p, author), author, admin);
        assertThatThrownBy(() -> update(r, "Sneaking in a change after approval."))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("change request");
        assertThat(revisionRows(r.getId())).isEqualTo(1);
    }

    @Test
    void VYB0907_AC5_deletingIsSoftHistoryStaysAndTheReasonIsAudited() {
        Requirement r = newRequirement(p, author);
        requirementService.delete(r.getId(), "duplicate of another", admin);

        assertThat(requirements.findById(r.getId())).isPresent().get().extracting(Requirement::isDeleted).isEqualTo(true);
        assertThat(revisionRows(r.getId())).isEqualTo(1);                          // history is kept
        assertThat(jdbc.queryForObject("SELECT after->>'reason' FROM audit_event WHERE object_id = ? AND action = 'requirement.deleted'",
            String.class, r.getId())).isEqualTo("duplicate of another");
        requirementService.delete(r.getId(), "again", admin);                       // a second call is not an error
        assertThat(auditCount(r.getId(), "requirement.deleted")).isEqualTo(1);
    }
}
