package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vyoog.evidence.TestCase;
import com.vyoog.evidence.TestCaseService;
import com.vyoog.evidence.TestExecutionService;
import com.vyoog.evidence.TestManagementService;
import com.vyoog.evidence.TestManagementService.RunDetail;
import com.vyoog.evidence.VerificationResult;
import com.vyoog.evidence.VerificationService;
import com.vyoog.requirements.Requirement;
import com.vyoog.trace.TraceGraphService;
import com.vyoog.trace.TraceLinkType;
import com.vyoog.trace.TraceObjectType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * VYB-0925 (F14): completing a manual run writes verification records, one per case and requirement the case
 * verifies, bound to the requirement revision frozen when the run was started. Quality evidence only: it never
 * writes {@code requirement.status} (CLAUDE.md rule 3).
 */
class TestVerificationIT extends IntegrationTestBase {

    @Autowired TestManagementService mgmt;
    @Autowired TestExecutionService exec;
    @Autowired TestCaseService testCases;
    @Autowired TraceGraphService trace;
    @Autowired VerificationService verification;

    private record Fx(UUID actor, Portfolio portfolio, Requirement req, TestCase tc, UUID suiteId, RunDetail run) {
        UUID id() { return run.run().id(); }
        UUID caseId() { return run.cases().get(0).id(); }
    }

    /** One step-less case verifying one requirement, in a PLANNED run. */
    private Fx fixture() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("tester");
        Requirement r = newRequirement(p, actor);
        TestCase tc = testCases.draft(unique("Case"), null, null, r.getId(), actor);
        var plan = mgmt.createPlan(unique("Plan"), null, p.applicationId(), null, actor);
        var suite = mgmt.createSuite(plan.id(), unique("Suite"), null, actor);
        mgmt.setSuiteCases(suite.id(), List.of(tc.getId()), actor);
        return new Fx(actor, p, r, tc, suite.id(), mgmt.createRun(suite.id(), null, null, actor));
    }

    private List<Map<String, Object>> rows(UUID runId) {
        return jdbc.queryForList("SELECT requirement_id, requirement_revision AS rev, test_case_id, result FROM verification "
            + "WHERE test_run_id = ? ORDER BY requirement_revision, result", runId);
    }

    private Map<String, Object> state(UUID requirementId) {
        return jdbc.queryForMap("SELECT is_verified, has_stale_evidence FROM requirement_verification_state WHERE id = ?", requirementId);
    }

    private Requirement edit(Requirement r, UUID actor) {
        return requirementService.update(r.getId(), r.getRevision(), r.getTitle(), r.getStatement() + " Revised.", r.getType(),
            r.getPriority(), r.getCapabilityId(), actor);
    }

    @Test
    void VYB0925_AC1_startingARunFreezesWhatEachCaseVerifiesAndAtWhichRevision() {
        Fx f = fixture();
        assertThat(mgmt.getRun(f.id()).cases().get(0).requirements()).isEmpty(); // nothing frozen while PLANNED

        RunDetail started = exec.start(f.id(), f.actor());

        var frozen = started.cases().get(0).requirements();
        assertThat(frozen).hasSize(1);
        assertThat(frozen.get(0).requirementId()).isEqualTo(f.req().getId());
        assertThat(frozen.get(0).key()).isEqualTo(f.req().getKey());
        assertThat(frozen.get(0).testedRevision()).isEqualTo(f.req().getRevision());
        assertThat(frozen.get(0).currentRevision()).isEqualTo(f.req().getRevision());
        assertThat(started.summary().verificationsRecorded()).isZero(); // nothing is written until the run is completed
        assertThat(rows(f.id())).isEmpty();
    }

    @Test
    void VYB0925_AC2_completingAPassingRunRecordsAPassBoundToTheRevisionAndTheRequirementBecomesVerified() {
        Fx f = fixture();
        assertThat(state(f.req().getId())).containsEntry("is_verified", false);
        String statusBefore = jdbc.queryForObject("SELECT status FROM requirement WHERE id = ?", String.class, f.req().getId());
        exec.start(f.id(), f.actor());
        exec.recordCaseResult(f.id(), f.caseId(), "PASS", null, f.actor());

        RunDetail done = exec.complete(f.id(), f.actor());

        assertThat(rows(f.id())).hasSize(1);
        assertThat(rows(f.id()).get(0)).containsEntry("result", "PASS").containsEntry("rev", f.req().getRevision())
            .containsEntry("requirement_id", f.req().getId()).containsEntry("test_case_id", f.tc().getId());
        assertThat(done.summary().verificationsRecorded()).isEqualTo(1);
        assertThat(state(f.req().getId())).containsEntry("is_verified", true).containsEntry("has_stale_evidence", false);
        assertThat(jdbc.queryForObject("SELECT has_test FROM requirement_coverage WHERE id = ?", Boolean.class, f.req().getId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM requirement WHERE id = ?", String.class, f.req().getId())).isEqualTo(statusBefore);
        assertThat(auditCount(f.id(), "test-run.verifications-recorded")).isEqualTo(1);
    }

    @Test
    void VYB0925_AC2_aFailingCaseRecordsAFailAndDoesNotVerifyTheRequirement() {
        Fx f = fixture();
        exec.start(f.id(), f.actor());
        exec.recordCaseResult(f.id(), f.caseId(), "FAIL", "page was blank", f.actor());
        exec.complete(f.id(), f.actor());

        assertThat(rows(f.id())).extracting(m -> m.get("result")).containsExactly("FAIL");
        assertThat(state(f.req().getId())).containsEntry("is_verified", false);
    }

    @Test
    void VYB0925_AC2_aBlockedCaseWasNotTestedSoItRecordsNothing() {
        Fx f = fixture();
        exec.start(f.id(), f.actor());
        exec.recordCaseResult(f.id(), f.caseId(), "BLOCKED", "env down", f.actor());
        RunDetail done = exec.complete(f.id(), f.actor());

        assertThat(rows(f.id())).isEmpty();
        assertThat(done.summary().verificationsRecorded()).isZero();
        assertThat(state(f.req().getId())).containsEntry("is_verified", false);
    }

    @Test
    void VYB0925_AC3_aRequirementEditedDuringTheRunIsRecordedAtTheRevisionTheTesterSawSoItIsStaleAtOnce() {
        Fx f = fixture();
        exec.start(f.id(), f.actor());
        Requirement edited = edit(f.req(), f.actor()); // revision moves while the tester is working
        assertThat(edited.getRevision()).isGreaterThan(f.req().getRevision());
        assertThat(mgmt.getRun(f.id()).cases().get(0).requirements().get(0).currentRevision()).isEqualTo(edited.getRevision());
        exec.recordCaseResult(f.id(), f.caseId(), "PASS", null, f.actor());

        RunDetail done = exec.complete(f.id(), f.actor());

        assertThat(rows(f.id()).get(0)).containsEntry("rev", f.req().getRevision()); // not the edited revision
        assertThat(state(f.req().getId())).containsEntry("is_verified", false).containsEntry("has_stale_evidence", true);
        var q = done.cases().get(0).requirements().get(0);
        assertThat(q.testedRevision()).isLessThan(q.currentRevision());
    }

    @Test
    void VYB0925_AC4_aCaseThatVerifiesSeveralRequirementsRecordsOneRowForEachAndOneThatVerifiesNoneRecordsNone() {
        Fx f = fixture();
        Requirement second = newRequirement(f.portfolio(), f.actor());
        trace.createLink(TraceObjectType.TEST, f.tc().getId(), TraceObjectType.REQUIREMENT, second.getId(), TraceLinkType.VERIFIES, f.actor());
        TestCase orphan = testCases.draft(unique("Orphan"), null, null, f.req().getId(), f.actor());
        jdbc.update("DELETE FROM trace_link WHERE from_id = ? AND link_type = 'VERIFIES'", orphan.getId());
        mgmt.setSuiteCases(f.suiteId(), List.of(f.tc().getId(), orphan.getId()), f.actor());
        RunDetail run = mgmt.createRun(f.suiteId(), null, null, f.actor());
        exec.start(run.run().id(), f.actor());
        exec.recordCaseResult(run.run().id(), run.cases().get(0).id(), "PASS", null, f.actor());
        exec.recordCaseResult(run.run().id(), run.cases().get(1).id(), "PASS", null, f.actor());

        RunDetail done = exec.complete(run.run().id(), f.actor());

        assertThat(rows(run.run().id())).extracting(m -> m.get("requirement_id")).containsExactlyInAnyOrder(f.req().getId(), second.getId());
        assertThat(done.summary().verificationsRecorded()).isEqualTo(2);
        assertThat(done.cases().get(1).requirements()).isEmpty();
    }

    @Test
    void VYB0925_AC5_aRetestRecordsAtItsOwnStartRevisionAndTheEarlierFailStaysOnRecord() {
        Fx f = fixture();
        exec.start(f.id(), f.actor());
        exec.recordCaseResult(f.id(), f.caseId(), "FAIL", "broke", f.actor());
        exec.complete(f.id(), f.actor());
        Requirement fixed = edit(f.req(), f.actor()); // the requirement is corrected before the retest

        RunDetail retest = exec.retest(f.id(), null, null, f.actor());
        exec.start(retest.run().id(), f.actor());
        exec.recordCaseResult(retest.run().id(), retest.cases().get(0).id(), "PASS", null, f.actor());
        exec.complete(retest.run().id(), f.actor());

        assertThat(rows(retest.run().id()).get(0)).containsEntry("result", "PASS").containsEntry("rev", fixed.getRevision());
        assertThat(rows(f.id()).get(0)).containsEntry("result", "FAIL").containsEntry("rev", f.req().getRevision()); // history kept
        assertThat(state(f.req().getId())).containsEntry("is_verified", true);
    }

    @Test
    void VYB0925_AC6_theRecordsAreWrittenOnceThenTheRunIsFinalAndNothingAboutCiChanges() {
        Fx f = fixture();
        exec.start(f.id(), f.actor());
        exec.recordCaseResult(f.id(), f.caseId(), "PASS", null, f.actor());
        exec.complete(f.id(), f.actor());
        try {
            exec.complete(f.id(), f.actor());
        } catch (IllegalStateException expected) {
            // a second completion is refused, so it cannot write the rows twice
        }
        assertThat(rows(f.id())).hasSize(1);

        // CI ingestion still writes its own rows for the same requirement, untouched
        var ci = verification.findOrCreateRun(unique("build"), "github-actions");
        verification.ingest(ci, new VerificationService.ResultInput(unique("T"), "ci", VerificationResult.PASS, List.of(f.req().getKey())));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM verification WHERE test_run_id = ?", Integer.class, ci.getId())).isEqualTo(1);
        assertThat(rows(f.id())).hasSize(1);
    }

    @Test
    void VYB0925_AC6_theRecordSurvivesTheLiveCaseBeingDeletedWithoutTheTestCaseId() {
        Fx f = fixture();
        exec.start(f.id(), f.actor());
        exec.recordCaseResult(f.id(), f.caseId(), "PASS", null, f.actor());
        jdbc.update("DELETE FROM test_case WHERE id = ?", f.tc().getId());

        exec.complete(f.id(), f.actor());

        assertThat(rows(f.id())).hasSize(1);
        assertThat(rows(f.id()).get(0)).containsEntry("test_case_id", null);
    }
}
