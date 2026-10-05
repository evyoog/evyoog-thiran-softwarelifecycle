package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vyoog.evidence.TestCase;
import com.vyoog.evidence.TestCaseService;
import com.vyoog.evidence.TestDefectService;
import com.vyoog.evidence.TestExecutionService;
import com.vyoog.evidence.TestManagementService;
import com.vyoog.evidence.TestManagementService.RunDetail;
import com.vyoog.evidence.TestManagementService.StepInput;
import com.vyoog.identity.AccessRole;
import com.vyoog.requirements.Requirement;
import com.vyoog.trace.TraceGraphService;
import com.vyoog.trace.TraceLinkType;
import com.vyoog.trace.TraceObjectType;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0926 (F14, F15): raising a defect from a failed step of a manual run, prefilled with the test and the run,
 * linked back to the step (no new text field), once per failed step.
 */
@AutoConfigureMockMvc
class TestDefectFromRunIT extends IntegrationTestBase {

    @Autowired TestManagementService mgmt;
    @Autowired TestExecutionService exec;
    @Autowired TestDefectService runDefects;
    @Autowired TestCaseService testCases;
    @Autowired TraceGraphService trace;
    @Autowired MockMvc mvc;

    private record Fx(UUID actor, Portfolio portfolio, Requirement req, TestCase caseA, TestCase caseB, UUID suiteId, RunDetail run) {
        UUID id() { return run.run().id(); }
        UUID step(int i) { return run.cases().get(0).steps().get(i).id(); }
        UUID stepless() { return run.cases().get(1).id(); }
    }

    /** Case A (two steps) and case B (no steps) both verifying one requirement; run STARTED; A step 1 FAIL, A step 2 PASS, B FAIL. */
    private Fx failedRun() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("tester");
        Requirement r = newRequirement(p, actor);
        TestCase a = testCases.draft(unique("A"), null, null, r.getId(), actor);
        TestCase b = testCases.draft(unique("B"), null, null, r.getId(), actor);
        mgmt.setSteps(a.getId(), List.of(new StepInput("Open the page", "It loads"), new StepInput("Submit", "Saved")), actor);
        var plan = mgmt.createPlan(unique("Plan"), null, p.applicationId(), null, actor);
        var suite = mgmt.createSuite(plan.id(), unique("Suite"), null, actor);
        mgmt.setSuiteCases(suite.id(), List.of(a.getId(), b.getId()), actor);
        RunDetail created = mgmt.createRun(suite.id(), null, "build-9", actor);
        exec.start(created.run().id(), actor);
        RunDetail run = mgmt.getRun(created.run().id());
        Fx f = new Fx(actor, p, r, a, b, suite.id(), run);
        exec.recordStepResult(f.id(), f.step(0), "FAIL", "Got a 500", actor);
        exec.recordStepResult(f.id(), f.step(1), "PASS", null, actor);
        exec.recordCaseResult(f.id(), f.stepless(), "FAIL", "Blank page", actor);
        return f;
    }

    // ------------------------------------------------------------------ draft

    @Test
    void VYB0926_AC1_theDraftIsPrefilledWithTheTestTheStepTheRunAndTheRequirement() {
        Fx f = failedRun();

        TestDefectService.DefectDraft d = runDefects.draftFromStep(f.id(), f.step(0));

        assertThat(d.title()).isEqualTo(f.caseA().getKey() + " step 1 failed: Open the page");
        assertThat(d.severity()).isEqualTo("MEDIUM");
        assertThat(d.foundIn()).isEqualTo("QA");
        assertThat(d.requirementId()).isEqualTo(f.req().getId());
        assertThat(d.candidates()).extracting(TestManagementService.TestedRequirement::key).containsExactly(f.req().getKey());
        assertThat(d.testKey()).isEqualTo(f.caseA().getKey());
        assertThat(d.testTitle()).isEqualTo(f.caseA().getTitle());
        assertThat(d.stepPosition()).isEqualTo(1);
        assertThat(d.action()).isEqualTo("Open the page");
        assertThat(d.expectedResult()).isEqualTo("It loads");
        assertThat(d.actualResult()).isEqualTo("Got a 500");
        assertThat(d.runId()).isEqualTo(f.id());
        assertThat(d.buildLabel()).isEqualTo("build-9");
        assertThat(d.planName()).isNotBlank();
        assertThat(d.suiteName()).isNotBlank();
        assertThat(d.existingDefect()).isNull();
        // reading a draft changes nothing
        assertThat(jdbc.queryForObject("SELECT count(*) FROM defect WHERE raised_from_run_step_id = ?", Integer.class, f.step(0))).isZero();
    }

    // ------------------------------------------------------------------ raise

    @Test
    void VYB0926_AC2_aFailedStepRaisesAnOpenDefectLinkedToTheStepWithTheDraftsValuesAndShownOnTheRun() {
        Fx f = failedRun();

        TestDefectService.Raised raised = runDefects.raiseFromStep(f.id(), f.step(0), null, null, null, null, f.actor());

        var d = raised.defect();
        assertThat(d.getKey()).matches("DEF-\\d+|D-\\d+|[A-Z]+-\\d+");
        assertThat(d.getTitle()).isEqualTo(f.caseA().getKey() + " step 1 failed: Open the page");
        assertThat(d.getSeverity().name()).isEqualTo("MEDIUM");
        assertThat(d.getFoundIn().name()).isEqualTo("QA");
        assertThat(d.getState().name()).isEqualTo("OPEN");
        assertThat(d.getRequirementId()).isEqualTo(f.req().getId());
        assertThat(raised.runStepId()).isEqualTo(f.step(0));
        assertThat(jdbc.queryForObject("SELECT raised_from_run_step_id FROM defect WHERE id = ?", UUID.class, d.getId())).isEqualTo(f.step(0));
        assertThat(auditCount(d.getId(), "defect.raised")).isEqualTo(1);
        assertThat(auditCount(f.id(), "test-run.defect-raised")).isEqualTo(1);
        RunDetail run = mgmt.getRun(f.id());
        assertThat(run.cases().get(0).steps().get(0).defect().key()).isEqualTo(d.getKey());
        assertThat(run.cases().get(0).steps().get(1).defect()).isNull();
        assertThat(runDefects.draftFromStep(f.id(), f.step(0)).existingDefect().key()).isEqualTo(d.getKey());
    }

    @Test
    void VYB0926_AC2_anythingTheCallerSuppliesWinsOverTheDraft() {
        Fx f = failedRun();
        var d = runDefects.raiseFromStep(f.id(), f.step(0), "  Checkout 500s  ", "CRITICAL", "UAT", f.req().getId(), f.actor()).defect();
        assertThat(d.getTitle()).isEqualTo("Checkout 500s");
        assertThat(d.getSeverity().name()).isEqualTo("CRITICAL");
        assertThat(d.getFoundIn().name()).isEqualTo("UAT");
        assertThatThrownBy(() -> runDefects.raiseFromCase(f.id(), f.stepless(), null, "SEVERE", null, null, f.actor()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM defect WHERE raised_from_run_case_id = ?", Integer.class, f.stepless())).isZero();
    }

    @Test
    void VYB0926_AC3_onlyAFailedStepOfAStartedRunCanRaiseADefect() {
        Fx f = failedRun();
        assertThatThrownBy(() -> runDefects.raiseFromStep(f.id(), f.step(1), null, null, null, null, f.actor()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("failed step"); // it passed
        assertThatThrownBy(() -> runDefects.draftFromStep(UUID.randomUUID(), f.step(0))).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> runDefects.raiseFromStep(f.id(), UUID.randomUUID(), null, null, null, null, f.actor()))
            .isInstanceOf(NoSuchElementException.class);

        RunDetail planned = mgmt.createRun(f.suiteId(), null, null, f.actor());
        UUID plannedStep = planned.cases().get(0).steps().get(0).id();
        assertThatThrownBy(() -> runDefects.raiseFromStep(planned.run().id(), plannedStep, null, null, null, null, f.actor()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Start the run");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM defect WHERE raised_from_run_step_id IS NOT NULL AND raised_from_run_step_id IN (?, ?)",
            Integer.class, f.step(1), plannedStep)).isZero();
    }

    @Test
    void VYB0926_AC3_aStepWithNoResultYetCannotRaiseOne() {
        Fx f = failedRun();
        RunDetail second = mgmt.createRun(f.suiteId(), null, null, f.actor());
        exec.start(second.run().id(), f.actor());
        UUID untouched = mgmt.getRun(second.run().id()).cases().get(0).steps().get(0).id();
        assertThatThrownBy(() -> runDefects.raiseFromStep(second.run().id(), untouched, null, null, null, null, f.actor()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("failed step");
    }

    @Test
    void VYB0926_AC4_oneDefectPerFailedStepASecondAttemptNamesTheFirst() {
        Fx f = failedRun();
        var first = runDefects.raiseFromStep(f.id(), f.step(0), null, null, null, null, f.actor()).defect();

        assertThatThrownBy(() -> runDefects.raiseFromStep(f.id(), f.step(0), "again", null, null, null, f.actor()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining(first.getKey());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM defect WHERE raised_from_run_step_id = ?", Integer.class, f.step(0))).isEqualTo(1);
        // the database holds the rule too, whatever the service does
        assertThatThrownBy(() -> jdbc.update("INSERT INTO defect (key, title, severity, found_in, raised_from_run_step_id) VALUES (?, 'x', 'LOW', 'QA', ?)",
            unique("DX"), f.step(0))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void VYB0926_AC5_whichRequirementTheDefectIsAgainstIsOnlyOneTheCaseVerifiedWhenTheRunStarted() {
        Fx f = failedRun();
        Requirement unrelated = newRequirement(f.portfolio(), f.actor());
        assertThatThrownBy(() -> runDefects.raiseFromStep(f.id(), f.step(0), null, null, null, unrelated.getId(), f.actor()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("did not verify");

        // a requirement linked after the run started is not one the run froze
        Requirement later = newRequirement(f.portfolio(), f.actor());
        trace.createLink(TraceObjectType.TEST, f.caseA().getId(), TraceObjectType.REQUIREMENT, later.getId(), TraceLinkType.VERIFIES, f.actor());
        assertThat(runDefects.draftFromStep(f.id(), f.step(0)).candidates()).hasSize(1);
        assertThat(runDefects.raiseFromStep(f.id(), f.step(0), null, null, null, null, f.actor()).defect().getRequirementId()).isEqualTo(f.req().getId());
    }

    @Test
    void VYB0926_AC5_severalVerifiedRequirementsMustBeChosenBetweenAndNoneLeavesTheDefectUntraced() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("tester");
        Requirement one = newRequirement(p, actor), two = newRequirement(p, actor);
        TestCase many = testCases.draft(unique("Many"), null, null, one.getId(), actor);
        trace.createLink(TraceObjectType.TEST, many.getId(), TraceObjectType.REQUIREMENT, two.getId(), TraceLinkType.VERIFIES, actor);
        TestCase none = testCases.draft(unique("None"), null, null, one.getId(), actor);
        jdbc.update("DELETE FROM trace_link WHERE from_id = ? AND link_type = 'VERIFIES'", none.getId());
        var plan = mgmt.createPlan(unique("Plan"), null, p.applicationId(), null, actor);
        var suite = mgmt.createSuite(plan.id(), unique("Suite"), null, actor);
        mgmt.setSuiteCases(suite.id(), List.of(many.getId(), none.getId()), actor);
        UUID runId = mgmt.createRun(suite.id(), null, null, actor).run().id();
        exec.start(runId, actor);
        RunDetail run = mgmt.getRun(runId);
        UUID manyCase = run.cases().get(0).id(), noneCase = run.cases().get(1).id();
        exec.recordCaseResult(runId, manyCase, "FAIL", "x", actor);
        exec.recordCaseResult(runId, noneCase, "FAIL", "y", actor);

        assertThat(runDefects.draftFromCase(runId, manyCase).requirementId()).isNull(); // nothing to choose for the caller
        assertThat(runDefects.draftFromCase(runId, manyCase).candidates()).hasSize(2);
        assertThatThrownBy(() -> runDefects.raiseFromCase(runId, manyCase, null, null, null, null, actor))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2 requirements");
        assertThat(runDefects.raiseFromCase(runId, manyCase, null, null, null, two.getId(), actor).defect().getRequirementId()).isEqualTo(two.getId());

        var untraced = runDefects.raiseFromCase(runId, noneCase, null, null, null, null, actor).defect();
        assertThat(untraced.isUntraced()).isTrue();
    }

    @Test
    void VYB0926_AC6_aFailedCaseWithNoStepsRaisesFromTheCaseAndACaseWithStepsOnlyFromItsSteps() {
        Fx f = failedRun();
        assertThatThrownBy(() -> runDefects.raiseFromCase(f.id(), f.run().cases().get(0).id(), null, null, null, null, f.actor()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("has steps");

        TestDefectService.DefectDraft draft = runDefects.draftFromCase(f.id(), f.stepless());
        assertThat(draft.title()).isEqualTo(f.caseB().getKey() + " failed: " + f.caseB().getTitle());
        assertThat(draft.stepPosition()).isNull();
        assertThat(draft.actualResult()).isEqualTo("Blank page");

        TestDefectService.Raised raised = runDefects.raiseFromCase(f.id(), f.stepless(), null, null, null, null, f.actor());
        assertThat(raised.runCaseId()).isEqualTo(f.stepless());
        assertThat(raised.runStepId()).isNull();
        assertThat(mgmt.getRun(f.id()).cases().get(1).defect().key()).isEqualTo(raised.defect().getKey());
        assertThatThrownBy(() -> runDefects.raiseFromCase(f.id(), f.stepless(), null, null, null, null, f.actor()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining(raised.defect().getKey());
    }

    @Test
    void VYB0926_AC7_aCompletedRunStillAllowsItAndARetestsFailureIsItsOwn() {
        Fx f = failedRun();
        exec.complete(f.id(), f.actor());
        var first = runDefects.raiseFromStep(f.id(), f.step(0), null, null, null, null, f.actor()).defect();

        RunDetail retest = exec.retest(f.id(), null, null, f.actor());
        exec.start(retest.run().id(), f.actor());
        RunDetail started = mgmt.getRun(retest.run().id());
        UUID retestStep = started.cases().get(0).steps().get(0).id();
        exec.recordStepResult(retest.run().id(), retestStep, "FAIL", "still broken", f.actor());
        var second = runDefects.raiseFromStep(retest.run().id(), retestStep, null, null, null, null, f.actor()).defect();

        assertThat(second.getKey()).isNotEqualTo(first.getKey());
        assertThat(runDefects.draftFromStep(retest.run().id(), retestStep).existingDefect().key()).isEqualTo(second.getKey());
    }

    @Test
    void VYB0926_AC8_raisingNeverChangesARunsResultsOrARequirementStatus() {
        Fx f = failedRun();
        String before = jdbc.queryForObject("SELECT status FROM requirement WHERE id = ?", String.class, f.req().getId());
        RunDetail beforeRun = mgmt.getRun(f.id());

        runDefects.raiseFromStep(f.id(), f.step(0), null, null, null, null, f.actor());

        RunDetail afterRun = mgmt.getRun(f.id());
        assertThat(afterRun.summary()).isEqualTo(beforeRun.summary());
        assertThat(afterRun.cases().get(0).steps().get(0).result()).isEqualTo("FAIL");
        assertThat(jdbc.queryForObject("SELECT status FROM requirement WHERE id = ?", String.class, f.req().getId())).isEqualTo(before);
    }

    // ------------------------------------------------------------------- HTTP

    private JwtRequestPostProcessor aPersonWith(AccessRole role, UUID capabilityId) {
        String id = unique("p");
        UUID user = users.upsert("sub-" + id, id + "@it.test", id).getId();
        grantOnCapability(user, role, capabilityId);
        return jwt().jwt(j -> j.subject("sub-" + id).claim("email", id + "@it.test").claim("preferred_username", id).claim("azp", "vyoog-web"));
    }

    @Test
    void VYB0926_AC9_raisingIsRefusedWith403ToANonTesterAndWorksForATesterWhileTheDraftIsReadableByAnySignedInPerson() throws Exception {
        Fx f = failedRun();
        Portfolio p = newPortfolio();
        JwtRequestPostProcessor tester = aPersonWith(AccessRole.TESTER, p.capabilityId());
        JwtRequestPostProcessor analyst = aPersonWith(AccessRole.BUSINESS_ANALYST, p.capabilityId());
        JwtRequestPostProcessor viewer = aPersonWith(AccessRole.VIEWER, p.capabilityId());
        String step = "/api/v1/test-runs/" + f.id() + "/steps/" + f.step(0);
        String cse = "/api/v1/test-runs/" + f.id() + "/cases/" + f.stepless();

        for (JwtRequestPostProcessor who : List.of(analyst, viewer)) {
            mvc.perform(post(step + "/defects").with(who)).andExpect(status().isForbidden());
            mvc.perform(post(cse + "/defects").with(who)).andExpect(status().isForbidden());
        }
        mvc.perform(post(step + "/defects")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM defect WHERE raised_from_run_step_id = ?", Integer.class, f.step(0))).isZero();

        mvc.perform(get(step + "/defect-draft").with(viewer)).andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value(f.caseA().getKey() + " step 1 failed: Open the page"))
            .andExpect(jsonPath("$.requirementId").value(f.req().getId().toString()))
            .andExpect(jsonPath("$.actualResult").value("Got a 500"));
        mvc.perform(post(step + "/defects").with(tester)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.runStepId").value(f.step(0).toString()))
            .andExpect(jsonPath("$.state").value("OPEN"))
            .andExpect(jsonPath("$.severity").value("MEDIUM"));
        mvc.perform(post(step + "/defects").with(tester)).andExpect(status().isConflict());
        mvc.perform(post(cse + "/defects").with(tester).contentType(MediaType.APPLICATION_JSON).content("{\"severity\":\"HIGH\",\"foundIn\":\"UAT\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.runCaseId").value(f.stepless().toString()))
            .andExpect(jsonPath("$.severity").value("HIGH"));
        mvc.perform(post("/api/v1/test-runs/" + f.id() + "/steps/" + f.step(1) + "/defects").with(tester)).andExpect(status().isConflict()); // that step passed
        mvc.perform(get("/api/v1/test-runs/" + f.id()).with(viewer)).andExpect(status().isOk())
            .andExpect(jsonPath("$.cases[0].steps[0].defectKey").exists())
            .andExpect(jsonPath("$.cases[1].defectKey").exists());
    }
}
