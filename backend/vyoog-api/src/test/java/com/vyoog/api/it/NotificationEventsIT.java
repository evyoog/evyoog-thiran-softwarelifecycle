package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vyoog.evidence.TestCase;
import com.vyoog.evidence.TestCaseService;
import com.vyoog.evidence.TestExecutionService;
import com.vyoog.evidence.TestManagementService;
import com.vyoog.evidence.VerificationResult;
import com.vyoog.evidence.VerificationService;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.ScopeType;
import com.vyoog.notify.NotificationSettingsService;
import com.vyoog.release.ReleaseLifecycleService;
import com.vyoog.release.ReleaseService;
import com.vyoog.release.ReleaseState;
import com.vyoog.requirements.BulkEditService;
import com.vyoog.requirements.CommentService;
import com.vyoog.requirements.Placement;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * VYB-0955 (F21, D47): who is told about review, approval, release, test-failure and mention events. Against a real PostgreSQL, through
 * the services that make each change, so the notification is seen to ride on the real write. Each test makes its own people and place.
 */
class NotificationEventsIT extends NotificationITBase {

    @Autowired VerificationService verification;
    @Autowired CommentService comments;
    @Autowired BulkEditService bulk;
    @Autowired ReleaseService releaseService;
    @Autowired ReleaseLifecycleService lifecycle;
    @Autowired NotificationSettingsService chatSettings;
    @Autowired TestManagementService mgmt;
    @Autowired TestExecutionService exec;
    @Autowired TestCaseService testCases;

    // ------------------------------------------------------------------ review and decision

    @Test
    void VYB0955_AC1_aSubmissionTellsTheReviewersWhereItSitsAndNobodyElse() {
        Portfolio p = newPortfolio();
        Portfolio elsewhere = newPortfolio();
        UUID author = newUser("author");
        UUID reviewer = grant(newUser("reviewer"), AccessRole.REVIEWER, ScopeType.CAPABILITY, p.capabilityId());
        UUID appReviewer = grant(newUser("appreviewer"), AccessRole.REVIEWER, ScopeType.APP, p.applicationId());
        UUID platformReviewer = grant(newUser("platformreviewer"), AccessRole.REVIEWER, ScopeType.PLATFORM, null);
        UUID otherPlaceReviewer = grant(newUser("otherreviewer"), AccessRole.REVIEWER, ScopeType.CAPABILITY, elsewhere.capabilityId());
        UUID approverOnly = grant(newUser("approver"), AccessRole.APPROVER, ScopeType.CAPABILITY, p.capabilityId());
        UUID revoked = grant(newUser("revoked"), AccessRole.REVIEWER, ScopeType.CAPABILITY, p.capabilityId());
        jdbc.update("UPDATE access_grant SET revoked_at = now() WHERE user_id = ?", revoked);
        UUID expired = newUser("expired");
        grants.grant(expired, AccessRole.REVIEWER, ScopeType.CAPABILITY, p.capabilityId(), java.time.Instant.now().minusSeconds(60), expired);
        UUID departed = grant(newUser("departed"), AccessRole.REVIEWER, ScopeType.CAPABILITY, p.capabilityId());
        jdbc.update("UPDATE app_user SET status = 'DEPARTED' WHERE id = ?", departed);

        Requirement r = submit(newRequirement(p, author), author);

        for (UUID told : List.of(reviewer, appReviewer, platformReviewer)) {
            assertThat(inbox(told, "review-requested")).as("a reviewer at or above the place is told").isEqualTo(1);
        }
        for (UUID notTold : List.of(otherPlaceReviewer, approverOnly, revoked, expired, departed, author)) {
            assertThat(inboxTotal(notTold)).as("not a reviewer here, not live, not active, or the author").isZero();
        }
        assertThat(subtitleOf(reviewer, "review-requested")).isEqualTo(r.getTitle());
        assertThat(jdbc.queryForObject("SELECT link FROM notification WHERE user_id = ?", String.class, reviewer)).isEqualTo("/requirements/" + r.getId());
    }

    @Test
    void VYB0955_AC2_aReviewedRequirementTellsTheApproversAndNeverAsksItsAuthorToDecide() {
        Portfolio p = newPortfolio();
        UUID author = grant(newUser("authorapprover"), AccessRole.APPROVER, ScopeType.CAPABILITY, p.capabilityId()); // an author who also holds the role
        UUID reviewer = grant(newUser("reviewer"), AccessRole.REVIEWER, ScopeType.CAPABILITY, p.capabilityId());
        UUID approver = grant(newUser("approver"), AccessRole.APPROVER, ScopeType.CAPABILITY, p.capabilityId());
        UUID productApprover = grant(newUser("productapprover"), AccessRole.APPROVER, ScopeType.PRODUCT, p.productId());
        Requirement r = submit(newRequirement(p, author), author);

        move(r, RequirementStatus.REVIEWED, null, reviewer);

        assertThat(inbox(approver, "approval-requested")).isEqualTo(1);
        assertThat(inbox(productApprover, "approval-requested")).as("a grant higher up the same chain counts").isEqualTo(1);
        assertThat(inbox(author, "approval-requested")).as("SoD: the author may not decide it").isZero();
        assertThat(inbox(reviewer, "approval-requested")).as("the person who made the move is not told of it").isZero();
    }

    @Test
    void VYB0955_AC3_approvalRejectionAndSendingBackTellTheAuthorWithTheReasonAndNotTheActor() {
        Portfolio p = newPortfolio();
        UUID creator = newUser("creator");
        UUID owner = newUser("owner");
        UUID reviewer = grant(newUser("reviewer"), AccessRole.REVIEWER, ScopeType.CAPABILITY, p.capabilityId());
        UUID approver = grant(newUser("approver"), AccessRole.APPROVER, ScopeType.CAPABILITY, p.capabilityId());

        Requirement a = submit(newRequirement(p, creator), creator);
        jdbc.update("UPDATE requirement SET owner_id = ? WHERE id = ?", owner, a.getId());
        a = move(a, RequirementStatus.REVIEWED, null, reviewer);
        move(a, RequirementStatus.APPROVED, null, approver);
        assertThat(inbox(creator, "requirement-approved")).isEqualTo(1);
        assertThat(inbox(owner, "requirement-approved")).as("the owner and the creator are both told").isEqualTo(1);
        assertThat(inbox(approver, "requirement-approved")).isZero();

        Requirement b = submit(newRequirement(p, creator), creator);
        b = move(b, RequirementStatus.REVIEWED, null, reviewer);
        move(b, RequirementStatus.REJECTED, "Out of scope for this release", approver);
        assertThat(inbox(creator, "requirement-rejected")).isEqualTo(1);
        assertThat(subtitleOf(creator, "requirement-rejected")).isEqualTo("Reason: Out of scope for this release");

        Requirement c = submit(newRequirement(p, creator), creator);
        c = move(c, RequirementStatus.REVIEWED, null, reviewer);
        move(c, RequirementStatus.NEEDS_REVISION, "Say how fast", approver);
        assertThat(inbox(creator, "requirement-sent-back")).isEqualTo(1);
        assertThat(subtitleOf(creator, "requirement-sent-back")).isEqualTo("Reason: Say how fast");
    }

    @Test
    void VYB0955_AC4_aRequirementPlacedHigherUpTellsTheHoldersOfTheRoleAtAndAboveThatPlace() {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        UUID appReviewer = grant(newUser("appreviewer"), AccessRole.REVIEWER, ScopeType.APP, p.applicationId());
        UUID capReviewer = grant(newUser("capreviewer"), AccessRole.REVIEWER, ScopeType.CAPABILITY, p.capabilityId());
        Requirement r = requirementService.create("At application level", "The system shall be placed on the application.", "FUNCTIONAL", "MEDIUM",
            Placement.application(p.applicationId()), author);

        submit(r, author);

        assertThat(inbox(appReviewer, "review-requested")).isEqualTo(1);
        assertThat(inbox(capReviewer, "review-requested")).as("a grant below the place does not reach up").isZero();
    }

    @Test
    void VYB0955_AC5_aWithdrawalBackToDraftAndARefusedMoveTellNobody() {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        UUID reviewer = grant(newUser("reviewer"), AccessRole.REVIEWER, ScopeType.CAPABILITY, p.capabilityId());
        UUID approver = grant(newUser("approver"), AccessRole.APPROVER, ScopeType.CAPABILITY, p.capabilityId());
        Requirement r = submit(newRequirement(p, author), author);
        jdbc.update("DELETE FROM notification WHERE user_id IN (?, ?, ?)", reviewer, approver, author);

        Requirement draft = move(r, RequirementStatus.DRAFT, null, author);
        assertThat(draft.getStatus()).isEqualTo(RequirementStatus.DRAFT);
        assertThat(inboxTotal(reviewer) + inboxTotal(approver) + inboxTotal(author)).as("withdrawing tells nobody").isZero();

        // an approval attempt by someone who may not approve is refused, and the refusal tells nobody anything
        Requirement again = submit(draft, author);
        jdbc.update("DELETE FROM notification WHERE user_id IN (?, ?, ?)", reviewer, approver, author);
        UUID outsider = newUser("outsider");
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> move(again, RequirementStatus.REVIEWED, null, outsider));
        assertThat(inboxTotal(reviewer) + inboxTotal(approver) + inboxTotal(author)).isZero();
    }

    // ------------------------------------------------------------------ releases

    @Test
    void VYB0955_AC6_aReleaseMovingTellsTheApproversWhoseScopeHoldsSomethingCommittedToItNotTheOneWhoMovedIt() {
        Portfolio mine = newPortfolio();
        Portfolio other = newPortfolio();
        UUID admin = newAdministrator();
        UUID author = newUser("author");
        UUID approverHere = grant(newUser("approverhere"), AccessRole.APPROVER, ScopeType.CAPABILITY, mine.capabilityId());
        UUID approverElsewhere = grant(newUser("approverelsewhere"), AccessRole.APPROVER, ScopeType.CAPABILITY, other.capabilityId());
        UUID mover = grant(newUser("mover"), AccessRole.APPROVER, ScopeType.CAPABILITY, mine.capabilityId());
        Requirement req = approved(newRequirement(mine, author), author, admin);
        UUID release = releaseService.create(unique("Release")).getId();
        releaseService.commit(release, req.getId(), admin, "scope for the test");
        jdbc.update("DELETE FROM notification WHERE user_id IN (?, ?, ?)", approverHere, approverElsewhere, mover);

        lifecycle.transition(release, ReleaseState.OPEN, null, false, mover);

        assertThat(inbox(approverHere, "release-state-changed")).isEqualTo(1);
        assertThat(inbox(approverElsewhere, "release-state-changed")).as("nothing of theirs is in this release").isZero();
        assertThat(inbox(mover, "release-state-changed")).as("not told of their own action").isZero();
        assertThat(jdbc.queryForObject("SELECT title FROM notification WHERE user_id = ?", String.class, approverHere))
            .startsWith("Release ").endsWith(": PLANNED to OPEN");
    }

    // ------------------------------------------------------------------ test failures and mentions

    @Test
    void VYB0955_AC7_aFailedTestTellsTheAuthorAndAPassTellsNobody() {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        Requirement r = newRequirement(p, author);
        var run = verification.findOrCreateRun(unique("build"), unique("ci"));

        verification.ingest(run, new VerificationService.ResultInput(unique("TC"), "Login locks the account", VerificationResult.PASS, List.of(r.getKey())));
        assertThat(inboxTotal(author)).as("a pass is not news").isZero();

        verification.ingest(run, new VerificationService.ResultInput(unique("TC"), "Login locks the account", VerificationResult.FAIL, List.of(r.getKey())));

        assertThat(inbox(author, "test-failed")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title FROM notification WHERE user_id = ?", String.class, author)).isEqualTo("Test failed against " + r.getKey());
        assertThat(subtitleOf(author, "test-failed")).contains("Login locks the account").contains(run.getBuildLabel());
    }

    @Test
    void VYB0955_AC8_aMentionTellsThePersonMentionedAndNotTheAuthorOfTheComment() {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        UUID colleague = newUser("colleague");
        UUID commenter = newUser("commenter");
        Requirement r = newRequirement(p, author);
        String handle = jdbc.queryForObject("SELECT split_part(email, '@', 1) FROM app_user WHERE id = ?", String.class, colleague);
        String selfHandle = jdbc.queryForObject("SELECT split_part(email, '@', 1) FROM app_user WHERE id = ?", String.class, commenter);

        comments.add(r.getId(), commenter, "Can you check this, @" + handle + "? (and @" + selfHandle + " noted it, @nobody.here too)");

        assertThat(inbox(colleague, "mentioned")).isEqualTo(1);
        assertThat(inbox(commenter, "mentioned")).as("mentioning yourself tells you nothing").isZero();
        assertThat(inbox(author, "mentioned")).isZero();
        assertThat(subtitleOf(colleague, "mentioned")).startsWith("Can you check this");
    }

    @Test
    void VYB0955_AC9_aBulkEditMovesManyRequirementsAndEachTellsItsReviewersButChatGetsOnePostForTheBatch() {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        UUID reviewer = grant(newUser("reviewer"), AccessRole.REVIEWER, ScopeType.CAPABILITY, p.capabilityId());
        UUID admin = newAdministrator();
        chatSettings.setChatKinds(List.of("review-requested"), admin);
        List<UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Requirement r = newRequirement(p, author);
            ids.add(r.getId());
        }

        var result = bulk.apply(ids, new BulkEditService.Changes(RequirementStatus.IN_REVIEW, null, null, false, null, null, "no criteria yet, on purpose"), author);

        assertThat(result.outcomes()).allMatch(BulkEditService.RowOutcome::applied);
        assertThat(inbox(reviewer, "review-requested")).as("three of one kind in the window are one inbox item").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT occurrence_count FROM notification WHERE user_id = ?", Integer.class, reviewer)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_delivery WHERE channel = 'CHAT' AND kind = 'review-requested' AND dedupe_key = ?",
            Integer.class, "review-requested:" + result.batchId())).as("one chat post for the whole batch").isEqualTo(1);
        chatSettings.setChatKinds(List.of(), admin);
    }

    @Test
    void VYB0955_AC10_aManualRunThatEndsWithAFailedCaseTellsTheAuthorOfTheRequirementItTestedAndNotTheTester() {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        UUID tester = newUser("tester");
        Requirement r = newRequirement(p, author);
        TestCase failing = testCases.draft(unique("A"), null, null, r.getId(), tester);
        TestCase passing = testCases.draft(unique("B"), null, null, r.getId(), tester);
        mgmt.setSteps(failing.getId(), List.of(new TestManagementService.StepInput("a1", "e1"), new TestManagementService.StepInput("a2", "e2")), tester);
        var plan = mgmt.createPlan(unique("Plan"), null, p.applicationId(), null, tester);
        var suite = mgmt.createSuite(plan.id(), unique("Suite"), null, tester);
        mgmt.setSuiteCases(suite.id(), List.of(failing.getId(), passing.getId()), tester);
        var run = mgmt.createRun(suite.id(), null, unique("manual-build"), tester);
        exec.start(run.run().id(), tester);
        exec.recordStepResult(run.run().id(), run.cases().get(0).steps().get(0).id(), "PASS", null, tester);
        exec.recordStepResult(run.run().id(), run.cases().get(0).steps().get(1).id(), "FAIL", "Saw a 500 page", tester);
        exec.recordCaseResult(run.run().id(), run.cases().get(1).id(), "PASS", null, tester);
        assertThat(inboxTotal(author)).as("nothing is announced while the run is still being filled in").isZero();

        exec.complete(run.run().id(), tester);

        assertThat(inbox(author, "test-failed")).isEqualTo(1);
        assertThat(subtitleOf(author, "test-failed")).contains(run.cases().get(0).title());
        assertThat(inboxTotal(tester)).isZero();
    }
}
