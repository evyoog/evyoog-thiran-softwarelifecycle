package com.vyoog.requirements;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.GrantRequiredException;
import com.vyoog.identity.GrantResolver;
import com.vyoog.platform.audit.AuditEventRepository;
import com.vyoog.platform.audit.AuditService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * VYB-0121: bulk edit reached {@link Requirement#transitionTo} directly, which meant it
 * was a way around every guard {@link RequirementService#transition} applies. VYB-0813
 * (D17): that now includes per-edge author/reviewer/decision-maker/admin checks, not
 * just the reason/blocking-clarification guards.
 */
@ExtendWith(MockitoExtension.class)
class BulkEditGuardTest {

    @Mock RequirementRepository requirements;
    @Mock AcceptanceCriterionRepository criteria;
    @Mock AuditService audit;
    @Mock AuditEventRepository auditEvents;
    @Mock JdbcTemplate jdbc;
    @Mock GrantResolver grantResolver;

    BulkEditService service;
    UUID actor;       // the author of every fixture requirement in this file
    UUID decisionMaker; // a second identity, distinct from actor, for SoD-sensitive moves

    @BeforeEach
    void setUp() {
        service = new BulkEditService(
            requirements, criteria, audit, auditEvents, new ObjectMapper(), jdbc,
            new RequirementTransitionAuthorizer(grantResolver));
        actor = UUID.randomUUID();
        decisionMaker = UUID.randomUUID();
        lenient().when(requirements.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // A sane default so tests unrelated to the acceptance-criteria override don't
        // all have to stub it themselves; the tests that actually exercise that guard
        // override this with an explicit 0L.
        lenient().when(criteria.countByRequirementId(any())).thenReturn(1L);
    }

    /**
     * JPA assigns the id on save; a fixture built with {@code new} has none, and
     * {@code snapshot} reads it. Stamping one keeps the test honest about that ordering
     * rather than making the service tolerate a null it never sees in production.
     */
    private UUID given(Requirement r) {
        UUID id = UUID.randomUUID();
        try {
            var f = Requirement.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(r, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        lenient().when(requirements.findById(id)).thenReturn(Optional.of(r));
        return id;
    }

    private void grant(UUID userId, AccessRole role) {
        lenient().when(grantResolver.hasEffectiveRole(eq(userId), eq(role), any(), any())).thenReturn(true);
    }

    private Requirement draft() {
        return new Requirement("VY-1", "Lead capture", "The system shall capture a lead.", actor);
    }

    /** Walks the real machine — as {@code decisionMaker}, never {@code actor} (the author), past REVIEWED. */
    private Requirement at(RequirementStatus status) {
        Requirement r = draft();
        if (status == RequirementStatus.DRAFT) return r;
        r.transitionTo(RequirementStatus.IN_REVIEW, actor);
        if (status == RequirementStatus.IN_REVIEW) return r;
        r.transitionTo(RequirementStatus.REVIEWED, decisionMaker);
        if (status == RequirementStatus.REVIEWED) return r;
        if (status == RequirementStatus.REJECTED) {
            r.transitionTo(RequirementStatus.REJECTED, decisionMaker, "not viable right now");
            return r;
        }
        r.transitionTo(RequirementStatus.APPROVED, decisionMaker);
        return r;
    }

    // ── the reported bug (VYB-0121) ───────────────────────────────────────────

    @Test
    void VYB0121_AC1_settingTheStatusARowIsAlreadyInIsANoOpNotASkip() {
        Requirement r = draft();
        UUID id = given(r);

        var result = service.apply(List.of(id),
            new BulkEditService.Changes(RequirementStatus.DRAFT, "HIGH", null, false, null, null), actor);

        assertThat(result.outcomes()).singleElement()
            .satisfies(o -> assertThat(o.applied()).isTrue());
        // And the other field the user actually wanted still landed — before this, the
        // throw happened first and took the priority change down with it.
        assertThat(r.getPriority()).isEqualTo("HIGH");
        assertThat(r.getStatus()).isEqualTo(RequirementStatus.DRAFT);
    }

    @Test
    void VYB0121_AC1_anIllegalMoveSkipsOnlyThatRowAndSaysWhy() {
        UUID draftId = given(draft());
        UUID reviewId = given(at(RequirementStatus.IN_REVIEW));
        grant(actor, AccessRole.REVIEWER);

        var result = service.apply(List.of(draftId, reviewId),
            new BulkEditService.Changes(RequirementStatus.REVIEWED, null, null, false, null, null), actor);

        var byId = result.outcomes().stream().collect(
            java.util.stream.Collectors.toMap(BulkEditService.RowOutcome::id, o -> o));
        assertThat(byId.get(draftId).applied()).isFalse();
        assertThat(byId.get(draftId).reason()).contains("Invalid transition: DRAFT -> REVIEWED");
        assertThat(byId.get(reviewId).applied()).isTrue(); // IN_REVIEW → REVIEWED is legal, and actor holds REVIEWER
    }

    // ── VYB-0813 (D17): per-edge permission checks, mirrored from the single-transition endpoint ──

    @Test
    void VYB0813_AC1_movingToReviewedWithoutTheReviewerRoleIsSkippedNotApplied() {
        UUID id = given(at(RequirementStatus.IN_REVIEW));
        // No grant() call — actor holds no role at all.

        var result = service.apply(List.of(id),
            new BulkEditService.Changes(RequirementStatus.REVIEWED, null, null, false, null, null), actor);

        assertThat(result.outcomes()).singleElement().satisfies(o -> {
            assertThat(o.applied()).isFalse();
            assertThat(o.reason()).contains("REVIEWER");
        });
    }

    @Test
    void VYB0813_AC2_theAuthorCannotApproveTheirOwnRequirementEvenInBulk() {
        UUID id = given(at(RequirementStatus.REVIEWED));
        grant(actor, AccessRole.APPROVER); // actor holds the role, but is also the author

        var result = service.apply(List.of(id),
            new BulkEditService.Changes(RequirementStatus.APPROVED, null, null, false, null, null), actor);

        assertThat(result.outcomes()).singleElement().satisfies(o -> {
            assertThat(o.applied()).isFalse();
            assertThat(o.reason()).contains("author");
        });
    }

    @Test
    void VYB0813_AC2_aDecisionMakerWhoIsNotTheAuthorCanApproveInBulk() {
        UUID id = given(at(RequirementStatus.REVIEWED));
        grant(decisionMaker, AccessRole.APPROVER);

        var result = service.apply(List.of(id),
            new BulkEditService.Changes(RequirementStatus.APPROVED, null, null, false, null, null), decisionMaker);

        assertThat(result.outcomes()).singleElement().satisfies(o -> assertThat(o.applied()).isTrue());
    }

    @Test
    void VYB0813_AC3_bulkRejectionNeedsAReasonJustLikeASingleOne() {
        UUID id = given(at(RequirementStatus.REVIEWED));
        grant(decisionMaker, AccessRole.APPROVER);

        var without = service.apply(List.of(id),
            new BulkEditService.Changes(RequirementStatus.REJECTED, null, null, false, null, null), decisionMaker);
        assertThat(without.outcomes()).singleElement().satisfies(o -> {
            assertThat(o.applied()).isFalse();
            assertThat(o.reason()).contains("rejection requires a reason");
        });

        var with = service.apply(List.of(id),
            new BulkEditService.Changes(RequirementStatus.REJECTED, null, null, false, null, null, "duplicate of VY-9"),
            decisionMaker);
        assertThat(with.outcomes()).singleElement().satisfies(o -> assertThat(o.applied()).isTrue());
    }

    @Test
    void VYB0813_AC3_bulkSendBackForRevisionNeedsAReason() {
        UUID id = given(at(RequirementStatus.REVIEWED));
        grant(decisionMaker, AccessRole.APPROVER);

        var without = service.apply(List.of(id),
            new BulkEditService.Changes(RequirementStatus.NEEDS_REVISION, null, null, false, null, null), decisionMaker);
        assertThat(without.outcomes()).singleElement().satisfies(o -> {
            assertThat(o.applied()).isFalse();
            assertThat(o.reason()).contains("revision requires a reason");
        });

        var with = service.apply(List.of(id),
            new BulkEditService.Changes(RequirementStatus.NEEDS_REVISION, null, null, false, null, null, "tighten this up"),
            decisionMaker);
        assertThat(with.outcomes()).singleElement().satisfies(o -> assertThat(o.applied()).isTrue());
    }

    @Test
    void VYB0813_AC4_reopeningARejectedRowNeedsNoReason() {
        UUID id = given(at(RequirementStatus.REJECTED));
        grant(decisionMaker, AccessRole.APPROVER);

        var result = service.apply(List.of(id),
            new BulkEditService.Changes(RequirementStatus.NEEDS_REVISION, null, null, false, null, null), decisionMaker);

        assertThat(result.outcomes()).singleElement().satisfies(o -> assertThat(o.applied()).isTrue());
    }

    @Test
    void VYB0813_AC4_needsRevisionResubmitsOnlyByItsAuthor() {
        Requirement r = draft();
        UUID id = given(r);
        r.transitionTo(RequirementStatus.IN_REVIEW, actor);
        r.transitionTo(RequirementStatus.REVIEWED, decisionMaker);
        r.transitionTo(RequirementStatus.NEEDS_REVISION, decisionMaker, "needs work");

        var byOther = service.apply(List.of(id),
            new BulkEditService.Changes(RequirementStatus.IN_REVIEW, null, null, false, null, null), decisionMaker);
        assertThat(byOther.outcomes()).singleElement().satisfies(o -> {
            assertThat(o.applied()).isFalse();
            assertThat(o.reason()).contains("author");
        });

        var byAuthor = service.apply(List.of(id),
            new BulkEditService.Changes(RequirementStatus.IN_REVIEW, null, null, false, null, null), actor);
        assertThat(byAuthor.outcomes()).singleElement().satisfies(o -> assertThat(o.applied()).isTrue());
    }

    // ── guards bulk edit used to bypass, unrelated to roles ───────────────────

    @Test
    void VYB0121_AC1_submittingARequirementWithNoCriteriaNeedsTheSameOverrideAsASingleSubmit() {
        UUID id = given(draft());
        when(criteria.countByRequirementId(id)).thenReturn(0L);

        var without = service.apply(List.of(id),
            new BulkEditService.Changes(RequirementStatus.IN_REVIEW, null, null, false, null, null), actor);
        assertThat(without.outcomes()).singleElement().satisfies(o -> {
            assertThat(o.applied()).isFalse();
            assertThat(o.reason()).contains("no acceptance criteria");
        });
    }

    @Test
    void VYB0121_AC1_aRequirementThatHasCriteriaSubmitsWithoutAnOverride() {
        UUID id = given(draft());
        when(criteria.countByRequirementId(id)).thenReturn(3L);

        var result = service.apply(List.of(id),
            new BulkEditService.Changes(RequirementStatus.IN_REVIEW, null, null, false, null, null), actor);

        assertThat(result.outcomes()).singleElement().satisfies(o -> assertThat(o.applied()).isTrue());
    }

    @Test
    void VYB0121_AC1_theCriteriaCheckIsPerRowSoAMixedSelectionPartlyApplies() {
        UUID withCriteria = given(draft());
        Requirement second = draft();
        UUID without = given(second);
        when(criteria.countByRequirementId(withCriteria)).thenReturn(2L);
        when(criteria.countByRequirementId(without)).thenReturn(0L);

        var result = service.apply(List.of(withCriteria, without),
            new BulkEditService.Changes(RequirementStatus.IN_REVIEW, null, null, false, null, null), actor);

        var byId = result.outcomes().stream().collect(
            java.util.stream.Collectors.toMap(BulkEditService.RowOutcome::id, o -> o));
        assertThat(byId.get(withCriteria).applied()).isTrue();
        assertThat(byId.get(without).applied()).isFalse();
    }
}
