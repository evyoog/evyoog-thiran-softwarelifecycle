package com.vyoog.requirements;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.vyoog.brief.BriefStalenessService;
import com.vyoog.detection.DetectionSweepService;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.GrantRequiredException;
import com.vyoog.identity.GrantResolver;
import com.vyoog.platform.audit.AuditService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Pure unit tests against mocked repositories — no database. The DB-backed behaviour
 * (unique keys, jsonb audit columns, actual persistence) is covered by
 * {@code RequirementApiIT} instead.
 */
@ExtendWith(MockitoExtension.class)
class RequirementServiceTest {

    @Mock RequirementRepository requirements;
    @Mock RequirementRevisionRepository revisions;
    @Mock AcceptanceCriterionRepository criteria;
    @Mock RequirementKeyAllocator keys;
    @Mock AuditService audit;
    @Mock DetectionSweepService detection;
    @Mock BriefStalenessService briefStaleness;
    @Mock JdbcTemplate jdbc;
    @Mock RequirementEnrichmentService enrichment;
    @Mock GrantResolver grantResolver;

    RequirementService service;
    UUID actor;

    @BeforeEach
    void setUp() {
        // A real authorizer over a mocked GrantResolver: the tests below exercise the
        // actual author/SoD/role logic, not a mock standing in for it.
        service = new RequirementService(
            requirements, revisions, criteria, keys, audit, detection, briefStaleness,
            new QualityScoreService(), jdbc, enrichment, new RequirementTransitionAuthorizer(grantResolver));
        actor = UUID.randomUUID();
        lenient().when(requirements.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // A sane default so tests unrelated to the acceptance-criteria override don't
        // all have to stub it themselves; the tests that actually exercise that guard
        // override this with an explicit 0L.
        lenient().when(criteria.countByRequirementId(any())).thenReturn(1L);
    }

    @Test
    void createAllocatesAKeyAndWritesTheFirstRevision() {
        when(keys.next()).thenReturn("VY-1");

        Requirement r = service.create("Title", "Statement", null, null, Placement.unplaced(), actor);

        assertThat(r.getKey()).isEqualTo("VY-1");
        assertThat(r.getRevision()).isEqualTo(1);
        verify(revisions).save(argThat(rev -> rev.getRevision() == 1 && rev.getStatement().equals("Statement")));
        verify(audit).record(eq(actor), eq("requirement.created"), eq("REQUIREMENT"), any(), isNull(), any());
    }

    @Test
    void VYB0666_AC1_createDefersEnrichmentInsteadOfRunningDetectionAndEmbeddingInline() {
        // The bug this exists to fix: create() used to call detection + embedding
        // synchronously, so a bulk import running create() 39 times in one transaction
        // held that whole transaction open for 39 rows' worth of sequential AI calls.
        // Outside a real Spring transaction (exactly this test's situation) there is no
        // after-commit hook to hang the deferral off, so it falls back to running
        // enrichment straight away — but through the separate RequirementEnrichmentService
        // bean, never by calling detection/embeddings directly from here again.
        when(keys.next()).thenReturn("VY-1");

        Requirement r = service.create("Title", "Statement", null, null, Placement.unplaced(), actor);

        verify(enrichment).enrich(r.getId(), r.getRevision(), "Statement");
        verifyNoInteractions(detection);
    }

    @Test
    void unchangedSaveDoesNotBumpTheRevision() {
        Requirement r = existing("VY-1", "Title", "Statement");
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));

        Requirement result = service.update(r.getId(), 1, "Title", "Statement", "FUNCTIONAL", "MEDIUM", null, actor);

        assertThat(result.getRevision()).isEqualTo(1);
        verifyNoInteractions(revisions);
        verifyNoInteractions(audit);
    }

    @Test
    void whitespaceOnlyChangeIsTreatedAsUnchanged() {
        Requirement r = existing("VY-1", "Title", "Statement");
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));

        Requirement result = service.update(
            r.getId(), 1, "Title", "  Statement  \n", "FUNCTIONAL", "MEDIUM", null, actor);

        assertThat(result.getRevision()).isEqualTo(1);
        verifyNoInteractions(revisions);
    }

    @Test
    void materialChangeBumpsTheRevisionAndWritesASnapshot() {
        Requirement r = existing("VY-1", "Title", "Statement");
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));

        Requirement result = service.update(
            r.getId(), 1, "Title", "A materially different statement", "FUNCTIONAL", "MEDIUM", null, actor);

        assertThat(result.getRevision()).isEqualTo(2);
        verify(revisions).save(argThat(rev -> rev.getRevision() == 2));
        verify(audit).record(eq(actor), eq("requirement.revised"), eq("REQUIREMENT"), any(), any(), any());
    }

    @Test
    void staleRevisionIsRefusedBeforeTouchingAnything() {
        Requirement r = existing("VY-1", "Title", "Statement");
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));

        assertThatThrownBy(() ->
            service.update(r.getId(), 99, "Title", "New statement", "FUNCTIONAL", "MEDIUM", null, actor))
            .isInstanceOf(StaleRevisionException.class)
            .extracting(e -> ((StaleRevisionException) e).getCurrentRevision())
            .isEqualTo(1);
        verifyNoInteractions(revisions);
        verifyNoInteractions(audit);
    }

    @Test
    void editingAnApprovedRequirementDirectlyIsRefused() {
        Requirement r = asAuthor("VY-1", actor);
        approve(r);
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));

        assertThatThrownBy(() ->
            service.update(r.getId(), 1, "Title", "A different statement", "FUNCTIONAL", "MEDIUM", null, actor))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("change request");
        verifyNoInteractions(revisions);
    }

    @Test
    void changeRequestEditPathBypassesTheApprovedGate() {
        Requirement r = asAuthor("VY-1", actor);
        approve(r);
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));

        Requirement result = service.applyChangeRequestEdit(
            r.getId(), 1, "Title", "A different statement", "FUNCTIONAL", "MEDIUM", null, actor);

        assertThat(result.getRevision()).isEqualTo(2);
        verify(audit).record(eq(actor), eq("requirement.revised_via_change_request"),
            eq("REQUIREMENT"), any(), any(), any());
    }

    // ── VYB-0813: the eight-edge state machine, exhaustively ─────────────────────

    @Test
    void VYB0813_AC1_draftToInReviewByItsAuthorSucceeds() {
        Requirement r = asAuthor("VY-1", actor);
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));

        Requirement result = service.transition(r.getId(), 1, RequirementStatus.IN_REVIEW, null, actor);

        assertThat(result.getStatus()).isEqualTo(RequirementStatus.IN_REVIEW);
        assertThat(result.getPreviousStatus()).isEqualTo("DRAFT");
        assertThat(result.getChangedBy()).isEqualTo(actor);
        assertThat(result.getChangedAt()).isNotNull();
    }

    @Test
    void VYB0813_AC1_draftToInReviewBySomeoneWhoIsNotTheAuthorIsRefused() {
        Requirement r = asAuthor("VY-1", UUID.randomUUID()); // someone else wrote it
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.transition(r.getId(), 1, RequirementStatus.IN_REVIEW, null, actor))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("author");
        verifyNoInteractions(audit);
    }

    @Test
    void VYB0813_AC1_inReviewWithdrawnBackToDraftByItsAuthor() {
        Requirement r = asAuthor("VY-1", actor);
        r.transitionTo(RequirementStatus.IN_REVIEW, actor);
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));

        Requirement result = service.transition(r.getId(), 1, RequirementStatus.DRAFT, null, actor);

        assertThat(result.getStatus()).isEqualTo(RequirementStatus.DRAFT);
        assertThat(result.getPreviousStatus()).isEqualTo("IN_REVIEW");
    }

    @Test
    void VYB0813_AC1_directRejectionFromInReviewIsNoLongerAllowed() {
        // The prior machine allowed IN_REVIEW -> REJECTED directly; this one does not —
        // a decision is only made once a review is actually complete.
        Requirement r = asAuthor("VY-1", actor);
        r.transitionTo(RequirementStatus.IN_REVIEW, actor);
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));

        assertThatThrownBy(() ->
            service.transition(r.getId(), 1, RequirementStatus.REJECTED, "no good", actor))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Invalid transition: IN_REVIEW -> REJECTED");
    }

    @Test
    void VYB0813_AC1_inReviewToReviewedNeedsTheReviewerRole() {
        Requirement r = asAuthor("VY-1", UUID.randomUUID());
        r.transitionTo(RequirementStatus.IN_REVIEW, actor);
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.transition(r.getId(), 1, RequirementStatus.REVIEWED, null, actor))
            .isInstanceOf(GrantRequiredException.class)
            .extracting(e -> ((GrantRequiredException) e).getRole())
            .isEqualTo(AccessRole.REVIEWER);
    }

    @Test
    void VYB0813_AC1_inReviewToReviewedByAReviewerSucceeds() {
        Requirement r = asAuthor("VY-1", UUID.randomUUID());
        r.transitionTo(RequirementStatus.IN_REVIEW, actor);
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));
        grant(actor, AccessRole.REVIEWER);

        Requirement result = service.transition(r.getId(), 1, RequirementStatus.REVIEWED, null, actor);

        assertThat(result.getStatus()).isEqualTo(RequirementStatus.REVIEWED);
    }

    @Test
    void VYB0815_administratorCanMarkReviewedWithoutTheReviewerRole() {
        Requirement r = asAuthor("VY-1", UUID.randomUUID());
        r.transitionTo(RequirementStatus.IN_REVIEW, actor);
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));
        grant(actor, AccessRole.ADMINISTRATOR);

        Requirement result = service.transition(r.getId(), 1, RequirementStatus.REVIEWED, null, actor);

        assertThat(result.getStatus()).isEqualTo(RequirementStatus.REVIEWED);
    }

    @Test
    void VYB0813_AC1_reviewedToApprovedByTheDecisionMakerSucceeds() {
        Requirement r = asAuthor("VY-1", UUID.randomUUID());
        r.transitionTo(RequirementStatus.IN_REVIEW, actor);
        r.transitionTo(RequirementStatus.REVIEWED, actor);
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));
        grant(actor, AccessRole.APPROVER);

        Requirement result = service.transition(r.getId(), 1, RequirementStatus.APPROVED, null, actor);

        assertThat(result.getStatus()).isEqualTo(RequirementStatus.APPROVED);
        assertThat(result.getVersion()).isEqualTo(1); // forking on approved edits is deferred (D17)
    }

    @Test
    void VYB0813_AC2_theRequirementsOwnAuthorCannotDecideOnItEvenWithTheApproverRole() {
        Requirement r = asAuthor("VY-1", actor); // actor is the author...
        r.transitionTo(RequirementStatus.IN_REVIEW, actor);
        r.transitionTo(RequirementStatus.REVIEWED, actor);
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));
        // ...and also holds APPROVER, but SoD refuses the combination anyway.
        lenient().when(grantResolver.hasEffectiveRole(eq(actor), eq(AccessRole.APPROVER), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.transition(r.getId(), 1, RequirementStatus.APPROVED, null, actor))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("author");
    }

    @Test
    void VYB0815_administratorCanDecideOnItsOwnSubmissionDespiteBeingItsAuthor() {
        Requirement r = asAuthor("VY-1", actor); // actor is the author...
        r.transitionTo(RequirementStatus.IN_REVIEW, actor);
        r.transitionTo(RequirementStatus.REVIEWED, actor);
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));
        grant(actor, AccessRole.ADMINISTRATOR); // ...but ADMINISTRATOR is exempt from SoD (VYB-0815).

        Requirement result = service.transition(r.getId(), 1, RequirementStatus.APPROVED, null, actor);

        assertThat(result.getStatus()).isEqualTo(RequirementStatus.APPROVED);
    }

    @Test
    void VYB0813_AC3_reviewedToRejectedRequiresAReason() {
        Requirement r = asAuthor("VY-1", UUID.randomUUID());
        r.transitionTo(RequirementStatus.IN_REVIEW, actor);
        r.transitionTo(RequirementStatus.REVIEWED, actor);
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));
        grant(actor, AccessRole.APPROVER);

        assertThatThrownBy(() -> service.transition(r.getId(), 1, RequirementStatus.REJECTED, null, actor))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reason");
    }

    @Test
    void VYB0813_AC3_reviewedToRejectedWithAReasonSucceeds() {
        Requirement r = asAuthor("VY-1", UUID.randomUUID());
        r.transitionTo(RequirementStatus.IN_REVIEW, actor);
        r.transitionTo(RequirementStatus.REVIEWED, actor);
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));
        grant(actor, AccessRole.APPROVER);

        Requirement result = service.transition(
            r.getId(), 1, RequirementStatus.REJECTED, "Does not satisfy the acceptance criteria", actor);

        assertThat(result.getStatus()).isEqualTo(RequirementStatus.REJECTED);
        assertThat(result.getReason()).isEqualTo("Does not satisfy the acceptance criteria");
    }

    @Test
    void VYB0813_AC3_reviewedToNeedsRevisionRequiresAReasonAndIncrementsRevisionCount() {
        Requirement r = asAuthor("VY-1", UUID.randomUUID());
        r.transitionTo(RequirementStatus.IN_REVIEW, actor);
        r.transitionTo(RequirementStatus.REVIEWED, actor);
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));
        grant(actor, AccessRole.APPROVER);

        assertThatThrownBy(() -> service.transition(r.getId(), 1, RequirementStatus.NEEDS_REVISION, null, actor))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reason");

        Requirement result = service.transition(
            r.getId(), 1, RequirementStatus.NEEDS_REVISION, "Tighten the acceptance criteria", actor);

        assertThat(result.getStatus()).isEqualTo(RequirementStatus.NEEDS_REVISION);
        assertThat(result.getRevisionCount()).isEqualTo(1);
    }

    @Test
    void VYB0813_AC4_needsRevisionResubmitsStraightToInReviewSkippingDraft() {
        Requirement r = asAuthor("VY-1", actor);
        r.transitionTo(RequirementStatus.IN_REVIEW, actor);
        r.transitionTo(RequirementStatus.REVIEWED, actor);
        r.transitionTo(RequirementStatus.NEEDS_REVISION, actor, "needs work");
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));

        Requirement result = service.transition(r.getId(), 1, RequirementStatus.IN_REVIEW, null, actor);

        assertThat(result.getStatus()).isEqualTo(RequirementStatus.IN_REVIEW);
    }

    @Test
    void VYB0813_AC4_needsRevisionCannotBeResubmittedByAnyoneOtherThanItsAuthor() {
        Requirement r = asAuthor("VY-1", UUID.randomUUID());
        r.transitionTo(RequirementStatus.IN_REVIEW, actor);
        r.transitionTo(RequirementStatus.REVIEWED, actor);
        r.transitionTo(RequirementStatus.NEEDS_REVISION, actor, "needs work");
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.transition(r.getId(), 1, RequirementStatus.IN_REVIEW, null, actor))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("author");
    }

    @Test
    void VYB0813_AC5_rejectedReopensIntoNeedsRevisionNotDraft() {
        Requirement r = asAuthor("VY-1", UUID.randomUUID());
        r.transitionTo(RequirementStatus.IN_REVIEW, actor);
        r.transitionTo(RequirementStatus.REVIEWED, actor);
        r.transitionTo(RequirementStatus.REJECTED, actor, "not viable right now");
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));
        grant(actor, AccessRole.APPROVER);

        // No reason required to reopen — REJECTED already carries one.
        Requirement result = service.transition(r.getId(), 1, RequirementStatus.NEEDS_REVISION, null, actor);

        assertThat(result.getStatus()).isEqualTo(RequirementStatus.NEEDS_REVISION);
        assertThat(result.getRevisionCount()).isEqualTo(1);
    }

    @Test
    void VYB0813_AC5_rejectedCanBeReopenedByAnAdministratorWithoutTheApproverRole() {
        Requirement r = asAuthor("VY-1", UUID.randomUUID());
        r.transitionTo(RequirementStatus.IN_REVIEW, actor);
        r.transitionTo(RequirementStatus.REVIEWED, actor);
        r.transitionTo(RequirementStatus.REJECTED, actor, "not viable right now");
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));
        grant(actor, AccessRole.ADMINISTRATOR);

        Requirement result = service.transition(r.getId(), 1, RequirementStatus.NEEDS_REVISION, null, actor);

        assertThat(result.getStatus()).isEqualTo(RequirementStatus.NEEDS_REVISION);
    }

    @Test
    void VYB0813_AC5_rejectedCannotBeReopenedWithNeitherApproverNorAdministrator() {
        Requirement r = asAuthor("VY-1", UUID.randomUUID());
        r.transitionTo(RequirementStatus.IN_REVIEW, actor);
        r.transitionTo(RequirementStatus.REVIEWED, actor);
        r.transitionTo(RequirementStatus.REJECTED, actor, "not viable right now");
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.transition(r.getId(), 1, RequirementStatus.NEEDS_REVISION, null, actor))
            .isInstanceOf(GrantRequiredException.class);
    }

    @Test
    void VYB0813_AC6_approvedIsFullyTerminalNoTransitionLeavesIt() {
        Requirement r = asAuthor("VY-1", actor);
        approve(r);
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.transition(r.getId(), 1, RequirementStatus.IN_REVIEW, null, actor))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Invalid transition: APPROVED -> IN_REVIEW");
        assertThatThrownBy(() -> service.transition(r.getId(), 1, RequirementStatus.REJECTED, "why not", actor))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Invalid transition: APPROVED -> REJECTED");
        assertThatThrownBy(() -> service.transition(r.getId(), 1, RequirementStatus.DRAFT, null, actor))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Invalid transition: APPROVED -> DRAFT");
    }

    @Test
    void submittingWithNoCriteriaAndNoOverrideReasonIsRefused() {
        Requirement r = asAuthor("VY-1", actor);
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));
        when(criteria.countByRequirementId(r.getId())).thenReturn(0L);

        assertThatThrownBy(() -> service.transition(r.getId(), 1, RequirementStatus.IN_REVIEW, null, actor))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submittingWithNoCriteriaButAnOverrideReasonIsAllowed() {
        Requirement r = asAuthor("VY-1", actor);
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));
        when(criteria.countByRequirementId(r.getId())).thenReturn(0L);

        Requirement result = service.transition(
            r.getId(), 1, RequirementStatus.IN_REVIEW, "Placeholder pending design review", actor);

        assertThat(result.getStatus()).isEqualTo(RequirementStatus.IN_REVIEW);
    }

    @Test
    void movingToReviewedWithAnOpenBlockingClarificationIsRefused() {
        Requirement r = asAuthor("VY-1", UUID.randomUUID());
        r.transitionTo(RequirementStatus.IN_REVIEW, actor);
        when(requirements.findById(r.getId())).thenReturn(Optional.of(r));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
        grant(actor, AccessRole.REVIEWER);

        assertThatThrownBy(() -> service.transition(r.getId(), 1, RequirementStatus.REVIEWED, null, actor))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blocking clarification");
    }

    private void grant(UUID userId, AccessRole role) {
        lenient().when(grantResolver.hasEffectiveRole(eq(userId), eq(role), any(), any())).thenReturn(true);
    }

    /** APPROVED, reached the long way (every real gate along the way still applies). */
    private void approve(Requirement r) {
        UUID approver = UUID.randomUUID();
        r.transitionTo(RequirementStatus.IN_REVIEW, r.getCreatedBy());
        r.transitionTo(RequirementStatus.REVIEWED, approver);
        r.transitionTo(RequirementStatus.APPROVED, approver);
    }

    private static Requirement existing(String key, String title, String statement) {
        Requirement r = new Requirement(key, title, statement, UUID.randomUUID());
        setId(r, UUID.randomUUID());
        return r;
    }

    /** A fresh DRAFT requirement written by {@code author} — the id most transition tests key SoD/role checks off. */
    private static Requirement asAuthor(String key, UUID author) {
        Requirement r = new Requirement(key, "Title", "Statement", author);
        setId(r, UUID.randomUUID());
        return r;
    }

    /** Test-only: the real id comes from the database via @GeneratedValue. */
    private static void setId(Requirement r, UUID id) {
        try {
            var field = Requirement.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(r, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
