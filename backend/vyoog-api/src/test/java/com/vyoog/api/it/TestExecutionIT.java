package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vyoog.evidence.TestCase;
import com.vyoog.evidence.TestCaseService;
import com.vyoog.evidence.TestExecutionService;
import com.vyoog.evidence.TestManagementService;
import com.vyoog.evidence.TestManagementService.RunDetail;
import com.vyoog.evidence.TestManagementService.StepInput;
import com.vyoog.identity.AccessRole;
import com.vyoog.requirements.Requirement;
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
 * VYB-0924a (F14): executing a manual run: start, a result and the actual result per step,
 * complete. Evidence attachments and retest are VYB-0924b; verification records are VYB-0925.
 */
@AutoConfigureMockMvc
class TestExecutionIT extends IntegrationTestBase {

    @Autowired TestManagementService mgmt;
    @Autowired TestExecutionService exec;
    @Autowired TestCaseService testCases;
    @Autowired MockMvc mvc;

    /** A run of two cases: the first with two steps, the second with none. */
    private record Fixture(UUID actor, UUID requirementId, RunDetail run) {
        UUID step(int i) { return run.cases().get(0).steps().get(i).id(); }
        UUID stepless() { return run.cases().get(1).id(); }
        UUID id() { return run.run().id(); }
    }

    private Fixture fixture() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("tester");
        Requirement r = newRequirement(p, actor);
        TestCase withSteps = testCases.draft(unique("A"), null, null, r.getId(), actor);
        TestCase noSteps = testCases.draft(unique("B"), null, null, r.getId(), actor);
        mgmt.setSteps(withSteps.getId(), List.of(new StepInput("a1", "e1"), new StepInput("a2", "e2")), actor);
        var plan = mgmt.createPlan(unique("Plan"), null, p.applicationId(), null, actor);
        var suite = mgmt.createSuite(plan.id(), unique("Suite"), null, actor);
        mgmt.setSuiteCases(suite.id(), List.of(withSteps.getId(), noSteps.getId()), actor);
        return new Fixture(actor, r.getId(), mgmt.createRun(suite.id(), null, null, actor));
    }

    // -------------------------------------------------------------- lifecycle

    @Test
    void VYB0924_AC1_aPlannedRunIsStartedOnceAndNothingCanBeRecordedBeforeThat() {
        Fixture f = fixture();
        assertThatThrownBy(() -> exec.recordStepResult(f.id(), f.step(0), "PASS", null, f.actor()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Start the run");
        assertThatThrownBy(() -> exec.complete(f.id(), f.actor())).isInstanceOf(IllegalStateException.class);

        RunDetail started = exec.start(f.id(), f.actor());

        assertThat(started.run().status()).isEqualTo("IN_PROGRESS");
        assertThat(started.run().startedAt()).isNotNull();
        assertThat(auditCount(f.id(), "test-run.started")).isEqualTo(1);
        assertThatThrownBy(() -> exec.start(f.id(), f.actor())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> exec.start(UUID.randomUUID(), f.actor())).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void VYB0924_AC2_eachStepGetsAResultAndAnActualResultAndWhoAndWhenAreKept() {
        Fixture f = fixture();
        exec.start(f.id(), f.actor());

        RunDetail run = exec.recordStepResult(f.id(), f.step(0), "PASS", null, f.actor());
        run = exec.recordStepResult(f.id(), f.step(1), "FAIL", "  Saw a 500 page  ", f.actor());

        var steps = run.cases().get(0).steps();
        assertThat(steps.get(0).result()).isEqualTo("PASS");
        assertThat(steps.get(0).actualResult()).isNull();
        assertThat(steps.get(1).result()).isEqualTo("FAIL");
        assertThat(steps.get(1).actualResult()).isEqualTo("Saw a 500 page");
        assertThat(steps.get(1).executedBy()).isEqualTo(f.actor());
        assertThat(steps.get(1).executedAt()).isNotNull();
        assertThat(steps.get(1).expectedResult()).isEqualTo("e2"); // the snapshot is untouched
        assertThat(auditCount(f.id(), "test-run.step-recorded")).isEqualTo(2);
    }

    @Test
    void VYB0924_AC2_aStepThatDidNotPassNeedsAnActualResultAndOnlyThreeResultsExist() {
        Fixture f = fixture();
        exec.start(f.id(), f.actor());
        assertThatThrownBy(() -> exec.recordStepResult(f.id(), f.step(0), "FAIL", "  ", f.actor()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("actual result");
        assertThatThrownBy(() -> exec.recordStepResult(f.id(), f.step(0), "BLOCKED", null, f.actor()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> exec.recordStepResult(f.id(), f.step(0), "MAYBE", "x", f.actor()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> exec.recordStepResult(f.id(), f.step(0), null, "x", f.actor()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(mgmt.getRun(f.id()).cases().get(0).steps().get(0).result()).isNull(); // nothing was recorded
    }

    @Test
    void VYB0924_AC2_recordingAgainReplacesTheResultAndTheAuditEventKeepsTheEarlierOne() {
        Fixture f = fixture();
        exec.start(f.id(), f.actor());
        exec.recordStepResult(f.id(), f.step(0), "FAIL", "broke", f.actor());
        RunDetail run = exec.recordStepResult(f.id(), f.step(0), "PASS", null, f.actor());

        assertThat(run.cases().get(0).steps().get(0).result()).isEqualTo("PASS");
        assertThat(run.cases().get(0).steps().get(0).actualResult()).isNull();
        assertThat(jdbc.queryForList("SELECT before->>'result' AS b, after->>'result' AS a FROM audit_event "
            + "WHERE object_id = ? AND action = 'test-run.step-recorded' ORDER BY occurred_at", f.id()))
            .extracting(m -> m.get("b") + ">" + m.get("a")).containsExactly("null>FAIL", "FAIL>PASS");
    }

    @Test
    void VYB0924_AC2_aStepOfAnotherRunCannotBeRecordedThroughThisOne() {
        Fixture f = fixture(), other = fixture();
        exec.start(f.id(), f.actor());
        assertThatThrownBy(() -> exec.recordStepResult(f.id(), other.step(0), "PASS", null, f.actor()))
            .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> exec.recordCaseResult(f.id(), other.stepless(), "PASS", null, f.actor()))
            .isInstanceOf(NoSuchElementException.class);
    }

    // ----------------------------------------------------------- case results

    @Test
    void VYB0924_AC3_aCasesResultIsDerivedFromItsStepsAndNeverStored() {
        Fixture f = fixture();
        exec.start(f.id(), f.actor());
        assertThat(mgmt.getRun(f.id()).cases().get(0).result()).isEqualTo("NOT_RUN");

        exec.recordStepResult(f.id(), f.step(0), "PASS", null, f.actor());
        assertThat(mgmt.getRun(f.id()).cases().get(0).result()).isEqualTo("NOT_RUN"); // one step still open
        exec.recordStepResult(f.id(), f.step(1), "BLOCKED", "env down", f.actor());
        assertThat(mgmt.getRun(f.id()).cases().get(0).result()).isEqualTo("BLOCKED");
        exec.recordStepResult(f.id(), f.step(0), "FAIL", "wrong", f.actor());
        assertThat(mgmt.getRun(f.id()).cases().get(0).result()).isEqualTo("FAIL"); // fail outranks blocked
        exec.recordStepResult(f.id(), f.step(0), "PASS", null, f.actor());
        exec.recordStepResult(f.id(), f.step(1), "PASS", null, f.actor());
        assertThat(mgmt.getRun(f.id()).cases().get(0).result()).isEqualTo("PASS");
        assertThat(jdbc.queryForObject("SELECT result FROM test_run_case WHERE id = ?", String.class, f.run().cases().get(0).id())).isNull();
    }

    @Test
    void VYB0924_AC3_aCaseWithNoStepsIsJudgedOnItselfAndACaseWithStepsIsNot() {
        Fixture f = fixture();
        exec.start(f.id(), f.actor());

        assertThatThrownBy(() -> exec.recordCaseResult(f.id(), f.run().cases().get(0).id(), "PASS", null, f.actor()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("has steps");
        assertThatThrownBy(() -> exec.recordCaseResult(f.id(), f.stepless(), "FAIL", null, f.actor()))
            .isInstanceOf(IllegalArgumentException.class);
        RunDetail run = exec.recordCaseResult(f.id(), f.stepless(), "FAIL", "page was blank", f.actor());

        assertThat(run.cases().get(1).result()).isEqualTo("FAIL");
        assertThat(run.cases().get(1).actualResult()).isEqualTo("page was blank");
        assertThat(run.cases().get(1).executedBy()).isEqualTo(f.actor());
        assertThat(run.summary().failed()).isEqualTo(1);
        assertThat(run.summary().notRun()).isEqualTo(1);
    }

    // ------------------------------------------------------------- completion

    @Test
    void VYB0924_AC4_aRunCompletesOnlyWhenEveryCaseHasAResultThenItIsFinal() {
        Fixture f = fixture();
        exec.start(f.id(), f.actor());
        exec.recordStepResult(f.id(), f.step(0), "PASS", null, f.actor());
        exec.recordCaseResult(f.id(), f.stepless(), "PASS", null, f.actor());
        assertThatThrownBy(() -> exec.complete(f.id(), f.actor()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("1 of 2");
        assertThat(mgmt.getRun(f.id()).run().status()).isEqualTo("IN_PROGRESS");

        exec.recordStepResult(f.id(), f.step(1), "FAIL", "nope", f.actor());
        RunDetail done = exec.complete(f.id(), f.actor());

        assertThat(done.run().status()).isEqualTo("COMPLETED");
        assertThat(done.run().completedAt()).isNotNull();
        assertThat(done.summary()).isEqualTo(new TestManagementService.RunSummary(2, 1, 1, 0, 0));
        assertThat(auditCount(f.id(), "test-run.completed")).isEqualTo(1);
        assertThatThrownBy(() -> exec.recordStepResult(f.id(), f.step(0), "FAIL", "late", f.actor()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("completed");
        assertThatThrownBy(() -> exec.recordCaseResult(f.id(), f.stepless(), "FAIL", "late", f.actor()))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> exec.complete(f.id(), f.actor())).isInstanceOf(IllegalStateException.class);
        assertThat(mgmt.getRun(f.id()).cases().get(0).steps().get(0).result()).isEqualTo("PASS");
    }

    @Test
    void VYB0924_AC5_executingARunNeverWritesVerificationOrChangesARequirementStatus() {
        Fixture f = fixture();
        String before = jdbc.queryForObject("SELECT status FROM requirement WHERE id = ?", String.class, f.requirementId());
        exec.start(f.id(), f.actor());
        exec.recordStepResult(f.id(), f.step(0), "PASS", null, f.actor());
        exec.recordStepResult(f.id(), f.step(1), "PASS", null, f.actor());
        exec.recordCaseResult(f.id(), f.stepless(), "PASS", null, f.actor());
        exec.complete(f.id(), f.actor());

        assertThat(jdbc.queryForObject("SELECT status FROM requirement WHERE id = ?", String.class, f.requirementId())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM verification WHERE test_run_id = ?", Integer.class, f.id())).isZero();
    }

    @Test
    void VYB0924_AC5_theDatabaseItselfRefusesAFailWithNoActualResultAndAResultWithNoWho() {
        Fixture f = fixture();
        UUID step = f.step(0);
        assertThatThrownBy(() -> jdbc.update("UPDATE test_run_step SET result = 'FAIL', executed_by = ?, executed_at = now() WHERE id = ?",
            f.actor(), step)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE test_run_step SET result = 'PASS' WHERE id = ?", step))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE test_run_step SET result = 'SKIPPED', executed_by = ?, executed_at = now() WHERE id = ?",
            f.actor(), step)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------- HTTP

    private JwtRequestPostProcessor aPersonWith(AccessRole role, UUID capabilityId) {
        String id = unique("p");
        UUID user = users.upsert("sub-" + id, id + "@it.test", id).getId();
        grantOnCapability(user, role, capabilityId);
        return jwt().jwt(j -> j.subject("sub-" + id).claim("email", id + "@it.test").claim("preferred_username", id).claim("azp", "vyoog-web"));
    }

    @Test
    void VYB0924_AC6_everyExecutionWriteIsRefusedWith403ToANonTesterAndAllowedToATester() throws Exception {
        Fixture f = fixture();
        Portfolio p = newPortfolio();
        JwtRequestPostProcessor tester = aPersonWith(AccessRole.TESTER, p.capabilityId());
        JwtRequestPostProcessor analyst = aPersonWith(AccessRole.BUSINESS_ANALYST, p.capabilityId());
        JwtRequestPostProcessor viewer = aPersonWith(AccessRole.VIEWER, p.capabilityId());
        String run = "/api/v1/test-runs/" + f.id();
        String body = "{\"result\":\"PASS\"}";

        for (JwtRequestPostProcessor who : List.of(analyst, viewer)) {
            mvc.perform(post(run + "/start").with(who)).andExpect(status().isForbidden());
            mvc.perform(put(run + "/steps/" + f.step(0) + "/result").with(who).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
            mvc.perform(put(run + "/cases/" + f.stepless() + "/result").with(who).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
            mvc.perform(post(run + "/complete").with(who)).andExpect(status().isForbidden());
        }
        assertThat(mgmt.getRun(f.id()).run().status()).isEqualTo("PLANNED");
        mvc.perform(post(run + "/start")).andExpect(status().isUnauthorized());

        mvc.perform(post(run + "/start").with(tester)).andExpect(status().isOk()).andExpect(jsonPath("$.run.status").value("IN_PROGRESS"));
        mvc.perform(put(run + "/steps/" + f.step(0) + "/result").with(tester).contentType(MediaType.APPLICATION_JSON)
            .content("{\"result\":\"FAIL\",\"actualResult\":\"it broke\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cases[0].steps[0].result").value("FAIL"))
            .andExpect(jsonPath("$.cases[0].result").value("FAIL"))
            .andExpect(jsonPath("$.summary.failed").value(1));
        mvc.perform(put(run + "/steps/" + f.step(1) + "/result").with(tester).contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"FAIL\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put(run + "/cases/" + f.run().cases().get(0).id() + "/result").with(tester).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict());
        mvc.perform(post(run + "/complete").with(tester)).andExpect(status().isConflict());
        mvc.perform(get(run).with(viewer)).andExpect(status().isOk()).andExpect(jsonPath("$.summary.total").value(2));
    }
}
