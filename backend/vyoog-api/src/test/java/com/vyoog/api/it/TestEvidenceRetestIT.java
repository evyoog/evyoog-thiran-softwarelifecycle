package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vyoog.attachments.AttachmentRejectedException;
import com.vyoog.evidence.TestCase;
import com.vyoog.evidence.TestCaseService;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0924b (F14): evidence on a run's results (stored as an attachment of a requirement the test case verifies)
 * and retest (a new run of the failed and blocked cases of a completed run).
 */
@AutoConfigureMockMvc
class TestEvidenceRetestIT extends IntegrationTestBase {

    @Autowired TestManagementService mgmt;
    @Autowired TestExecutionService exec;
    @Autowired TestCaseService testCases;
    @Autowired TraceGraphService trace;
    @Autowired MockMvc mvc;

    private static final byte[] PNG = {1, 2, 3, 4};

    /** Case A (two steps) and case B (no steps), both verifying one requirement; the run is started. */
    private record Fx(UUID actor, Portfolio portfolio, Requirement requirement, TestCase caseA, TestCase caseB, UUID suiteId, RunDetail run) {
        UUID id() { return run.run().id(); }
        UUID step(int i) { return run.cases().get(0).steps().get(i).id(); }
        UUID stepless() { return run.cases().get(1).id(); }
    }

    private Fx fixture() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("tester");
        Requirement r = newRequirement(p, actor);
        TestCase a = testCases.draft(unique("A"), null, null, r.getId(), actor);
        TestCase b = testCases.draft(unique("B"), null, null, r.getId(), actor);
        mgmt.setSteps(a.getId(), List.of(new StepInput("a1", "e1"), new StepInput("a2", "e2")), actor);
        var plan = mgmt.createPlan(unique("Plan"), null, p.applicationId(), null, actor);
        var suite = mgmt.createSuite(plan.id(), unique("Suite"), null, actor);
        mgmt.setSuiteCases(suite.id(), List.of(a.getId(), b.getId()), actor);
        RunDetail run = mgmt.createRun(suite.id(), null, null, actor);
        exec.start(run.run().id(), actor);
        return new Fx(actor, p, r, a, b, suite.id(), mgmt.getRun(run.run().id()));
    }

    /** Completes the fixture's run: case A step 1 FAIL, step 2 PASS; case B with the given result. */
    private RunDetail complete(Fx f, String caseBResult) {
        exec.recordStepResult(f.id(), f.step(0), "FAIL", "broke", f.actor());
        exec.recordStepResult(f.id(), f.step(1), "PASS", null, f.actor());
        exec.recordCaseResult(f.id(), f.stepless(), caseBResult, "PASS".equals(caseBResult) ? null : "nope", f.actor());
        return exec.complete(f.id(), f.actor());
    }

    // --------------------------------------------------------------- evidence

    @Test
    void VYB0924b_AC1_aStepsEvidenceIsStoredAsAnAttachmentOfTheRequirementItsCaseVerifiesAndShownOnTheStep() {
        Fx f = fixture();

        RunDetail run = exec.addStepEvidence(f.id(), f.step(0), null, "shot.png", "image/png", PNG, f.actor());

        var evidence = run.cases().get(0).steps().get(0).evidence();
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).requirementId()).isEqualTo(f.requirement().getId());
        assertThat(evidence.get(0).requirementKey()).isEqualTo(f.requirement().getKey());
        assertThat(evidence.get(0).filename()).startsWith("run-" + f.id().toString().substring(0, 8)).contains(f.caseA().getKey() + "-step1").endsWith("shot.png");
        assertThat(evidence.get(0).version()).isEqualTo(1);
        assertThat(evidence.get(0).sizeBytes()).isEqualTo(4L);
        assertThat(evidence.get(0).addedBy()).isEqualTo(f.actor());
        assertThat(run.cases().get(0).steps().get(1).evidence()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attachment WHERE requirement_id = ? AND filename = ?", Integer.class,
            f.requirement().getId(), evidence.get(0).filename())).isEqualTo(1);
        assertThat(auditCount(f.id(), "test-run.evidence-added")).isEqualTo(1);
    }

    @Test
    void VYB0924b_AC1_aCaseWithNoStepsTakesItsEvidenceOnTheCaseAndACaseWithStepsOnlyOnItsSteps() {
        Fx f = fixture();
        assertThatThrownBy(() -> exec.addCaseEvidence(f.id(), f.run().cases().get(0).id(), null, "x.png", "image/png", PNG, f.actor()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("has steps");

        RunDetail run = exec.addCaseEvidence(f.id(), f.stepless(), null, "log.txt", "text/plain", PNG, f.actor());

        assertThat(run.cases().get(1).evidence()).hasSize(1);
        assertThat(run.cases().get(1).evidence().get(0).filename()).contains(f.caseB().getKey()).endsWith("log.txt");
    }

    @Test
    void VYB0924b_AC1_evidencePointsAtTheExactVersionSoReUploadingTheSameNameNeverChangesAnEarlierOne() {
        Fx f = fixture();
        exec.addStepEvidence(f.id(), f.step(0), null, "shot.png", "image/png", PNG, f.actor());
        RunDetail run = exec.addStepEvidence(f.id(), f.step(0), null, "shot.png", "image/png", new byte[] {9, 9, 9, 9, 9}, f.actor());

        var evidence = run.cases().get(0).steps().get(0).evidence();
        assertThat(evidence).extracting(TestManagementService.Evidence::version).containsExactly(1, 2);
        assertThat(evidence).extracting(TestManagementService.Evidence::sizeBytes).containsExactly(4L, 5L);
        assertThat(evidence.get(0).attachmentId()).isEqualTo(evidence.get(1).attachmentId());
    }

    @Test
    void VYB0924b_AC2_whichRequirementTheFileAttachesToIsOnlyEverOneTheCaseVerifies() {
        Fx f = fixture();
        Requirement other = newRequirement(f.portfolio(), f.actor());
        Requirement unrelated = newRequirement(f.portfolio(), f.actor());

        // one verified requirement: may be omitted or named; an unrelated one is refused
        assertThatThrownBy(() -> exec.addStepEvidence(f.id(), f.step(0), unrelated.getId(), "a.png", "image/png", PNG, f.actor()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not verify");
        assertThat(exec.addStepEvidence(f.id(), f.step(0), f.requirement().getId(), "a.png", "image/png", PNG, f.actor())
            .cases().get(0).steps().get(0).evidence()).hasSize(1);

        // two verified requirements: it must be named
        trace.createLink(TraceObjectType.TEST, f.caseA().getId(), TraceObjectType.REQUIREMENT, other.getId(), TraceLinkType.VERIFIES, f.actor());
        assertThatThrownBy(() -> exec.addStepEvidence(f.id(), f.step(1), null, "b.png", "image/png", PNG, f.actor()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2 requirements");
        RunDetail run = exec.addStepEvidence(f.id(), f.step(1), other.getId(), "b.png", "image/png", PNG, f.actor());
        assertThat(run.cases().get(0).steps().get(1).evidence().get(0).requirementId()).isEqualTo(other.getId());
    }

    @Test
    void VYB0924b_AC2_aCaseThatVerifiesNothingHasNowhereToPutEvidence() {
        Fx f = fixture();
        jdbc.update("DELETE FROM trace_link WHERE from_id = ? AND link_type = 'VERIFIES'", f.caseA().getId());
        assertThatThrownBy(() -> exec.addStepEvidence(f.id(), f.step(0), null, "a.png", "image/png", PNG, f.actor()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("verifies no requirement");
        assertThat(mgmt.getRun(f.id()).cases().get(0).steps().get(0).evidence()).isEmpty();
    }

    @Test
    void VYB0924b_AC3_evidenceFollowsTheAttachmentRulesAndTheRunsLifecycle() {
        Fx f = fixture();
        assertThatThrownBy(() -> exec.addStepEvidence(f.id(), f.step(0), null, "run.exe", "application/octet-stream", PNG, f.actor()))
            .isInstanceOf(AttachmentRejectedException.class);
        assertThatThrownBy(() -> exec.addStepEvidence(f.id(), f.step(0), null, "empty.png", "image/png", new byte[0], f.actor()))
            .isInstanceOf(AttachmentRejectedException.class);
        assertThatThrownBy(() -> exec.addStepEvidence(f.id(), f.step(0), null, " ", "image/png", PNG, f.actor()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> exec.addStepEvidence(f.id(), UUID.randomUUID(), null, "a.png", "image/png", PNG, f.actor()))
            .isInstanceOf(NoSuchElementException.class);
        assertThat(mgmt.getRun(f.id()).cases().get(0).steps().get(0).evidence()).isEmpty(); // nothing was half-stored

        complete(f, "PASS");
        assertThatThrownBy(() -> exec.addStepEvidence(f.id(), f.step(0), null, "late.png", "image/png", PNG, f.actor()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("completed");
    }

    @Test
    void VYB0924b_AC3_evidenceCannotBeAddedBeforeTheRunIsStarted() {
        Fx f = fixture();
        RunDetail planned = mgmt.createRun(f.suiteId(), null, null, f.actor());
        UUID step = planned.cases().get(0).steps().get(0).id();
        assertThatThrownBy(() -> exec.addStepEvidence(planned.run().id(), step, null, "a.png", "image/png", PNG, f.actor()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Start the run");
    }

    // ---------------------------------------------------------------- retest

    @Test
    void VYB0924b_AC4_aRetestIsANewPlannedRunOfTheFailedAndBlockedCasesWithEveryResultBlank() {
        Fx f = fixture();
        RunDetail original = complete(f, "PASS"); // case A fails (step 1), case B passes

        RunDetail retest = exec.retest(f.id(), null, null, f.actor());

        assertThat(retest.run().id()).isNotEqualTo(f.id());
        assertThat(retest.run().retestOf()).isEqualTo(f.id());
        assertThat(retest.run().status()).isEqualTo("PLANNED");
        assertThat(retest.run().kind()).isEqualTo("MANUAL");
        assertThat(retest.run().suiteId()).isEqualTo(f.suiteId());
        assertThat(retest.cases()).extracting(TestManagementService.RunCase::key).containsExactly(f.caseA().getKey()); // not B: it passed
        assertThat(retest.cases().get(0).steps()).extracting(TestManagementService.RunStep::action).containsExactly("a1", "a2");
        assertThat(retest.cases().get(0).steps()).allSatisfy(st -> {
            assertThat(st.result()).isNull();
            assertThat(st.evidence()).isEmpty();
        });
        assertThat(retest.cases().get(0).result()).isEqualTo("NOT_RUN");
        assertThat(auditCount(retest.run().id(), "test-run.retest-created")).isEqualTo(1);
        // the original is exactly as it was
        RunDetail after = mgmt.getRun(f.id());
        assertThat(after.run().status()).isEqualTo("COMPLETED");
        assertThat(after.summary()).isEqualTo(original.summary());
        assertThat(after.cases().get(0).steps().get(0).result()).isEqualTo("FAIL");
    }

    @Test
    void VYB0924b_AC4_aBlockedCaseIsRetestedToo() {
        Fx f = fixture();
        exec.recordStepResult(f.id(), f.step(0), "PASS", null, f.actor());
        exec.recordStepResult(f.id(), f.step(1), "PASS", null, f.actor());
        exec.recordCaseResult(f.id(), f.stepless(), "BLOCKED", "env down", f.actor());
        exec.complete(f.id(), f.actor());

        RunDetail retest = exec.retest(f.id(), null, "  rc-2 ", f.actor());

        assertThat(retest.cases()).extracting(TestManagementService.RunCase::key).containsExactly(f.caseB().getKey());
        assertThat(retest.cases().get(0).position()).isEqualTo(1);
        assertThat(retest.run().buildLabel()).isEqualTo("rc-2");
    }

    @Test
    void VYB0924b_AC4_theRetestKeepsTheSnapshotNotTheLiveCase() {
        Fx f = fixture();
        complete(f, "PASS");
        mgmt.setSteps(f.caseA().getId(), List.of(new StepInput("rewritten", "rewritten")), f.actor());
        testCases.update(f.caseA().getId(), "Renamed", null, null, f.actor());

        RunDetail retest = exec.retest(f.id(), null, null, f.actor());

        assertThat(retest.cases().get(0).title()).isEqualTo(f.caseA().getTitle());
        assertThat(retest.cases().get(0).steps()).extracting(TestManagementService.RunStep::action).containsExactly("a1", "a2");
    }

    @Test
    void VYB0924b_AC5_onlyACompletedRunWithSomethingToRetestCanBeRetestedAndOnlyOneRetestAtATime() {
        Fx f = fixture();
        assertThatThrownBy(() -> exec.retest(f.id(), null, null, f.actor()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("completed run");

        // a run where everything passed has nothing to retest
        Fx clean = fixture();
        exec.recordStepResult(clean.id(), clean.step(0), "PASS", null, clean.actor());
        exec.recordStepResult(clean.id(), clean.step(1), "PASS", null, clean.actor());
        exec.recordCaseResult(clean.id(), clean.stepless(), "PASS", null, clean.actor());
        exec.complete(clean.id(), clean.actor());
        assertThatThrownBy(() -> exec.retest(clean.id(), null, null, clean.actor()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("nothing to retest");

        complete(f, "BLOCKED");
        RunDetail first = exec.retest(f.id(), null, null, f.actor());
        assertThatThrownBy(() -> exec.retest(f.id(), null, null, f.actor()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("already open");
        assertThatThrownBy(() -> exec.retest(f.id(), UUID.randomUUID(), null, f.actor())).isInstanceOf(IllegalStateException.class);

        // once the retest is finished, a retest of the retest is allowed and the chain is kept
        exec.start(first.run().id(), f.actor());
        UUID s0 = first.cases().get(0).steps().get(0).id(), s1 = first.cases().get(0).steps().get(1).id();
        exec.recordStepResult(first.run().id(), s0, "FAIL", "still broken", f.actor());
        exec.recordStepResult(first.run().id(), s1, "PASS", null, f.actor());
        exec.recordCaseResult(first.run().id(), first.cases().get(1).id(), "PASS", null, f.actor());
        exec.complete(first.run().id(), f.actor());
        RunDetail second = exec.retest(first.run().id(), null, null, f.actor());
        assertThat(second.run().retestOf()).isEqualTo(first.run().id());
        assertThat(second.cases()).extracting(TestManagementService.RunCase::key).containsExactly(f.caseA().getKey());
        assertThat(mgmt.listRuns(f.suiteId(), null, null)).hasSize(3);
    }

    @Test
    void VYB0924b_AC5_aRetestWritesNoVerificationOfItsOwnAndNothingChangesARequirementStatus() {
        Fx f = fixture();
        String before = jdbc.queryForObject("SELECT status FROM requirement WHERE id = ?", String.class, f.requirement().getId());
        exec.addStepEvidence(f.id(), f.step(0), null, "a.png", "image/png", PNG, f.actor());
        complete(f, "PASS");
        RunDetail retest = exec.retest(f.id(), null, null, f.actor());

        assertThat(jdbc.queryForObject("SELECT status FROM requirement WHERE id = ?", String.class, f.requirement().getId())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM verification WHERE test_run_id = ?", Integer.class, retest.run().id())).isZero();
    }

    @Test
    void VYB0924b_AC5_theDatabaseRefusesEvidenceOnBothAStepAndACaseOrOnNeither() {
        Fx f = fixture();
        UUID version = exec.addStepEvidence(f.id(), f.step(0), null, "a.png", "image/png", PNG, f.actor())
            .cases().get(0).steps().get(0).evidence().get(0).id();
        UUID versionId = jdbc.queryForObject("SELECT attachment_version_id FROM test_run_evidence WHERE id = ?", UUID.class, version);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO test_run_evidence (run_step_id, run_case_id, attachment_version_id) VALUES (?, ?, ?)",
            f.step(0), f.stepless(), versionId)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO test_run_evidence (attachment_version_id) VALUES (?)", versionId))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO test_run (kind, status, retest_of) VALUES ('CI', 'COMPLETED', ?)", f.id()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------- HTTP

    private JwtRequestPostProcessor aPersonWith(AccessRole role, UUID capabilityId) {
        String id = unique("p");
        UUID user = users.upsert("sub-" + id, id + "@it.test", id).getId();
        grantOnCapability(user, role, capabilityId);
        return jwt().jwt(j -> j.subject("sub-" + id).claim("email", id + "@it.test").claim("preferred_username", id).claim("azp", "vyoog-web"));
    }

    @Test
    void VYB0924b_AC6_evidenceAndRetestWritesAreRefusedWith403ToANonTesterAndWorkForATester() throws Exception {
        Fx f = fixture();
        Portfolio p = newPortfolio();
        JwtRequestPostProcessor tester = aPersonWith(AccessRole.TESTER, p.capabilityId());
        JwtRequestPostProcessor analyst = aPersonWith(AccessRole.BUSINESS_ANALYST, p.capabilityId());
        JwtRequestPostProcessor viewer = aPersonWith(AccessRole.VIEWER, p.capabilityId());
        String run = "/api/v1/test-runs/" + f.id();
        MockMultipartFile file = new MockMultipartFile("file", "shot.png", "image/png", PNG);

        for (JwtRequestPostProcessor who : List.of(analyst, viewer)) {
            mvc.perform(multipart(run + "/steps/" + f.step(0) + "/evidence").file(file).with(who)).andExpect(status().isForbidden());
            mvc.perform(multipart(run + "/cases/" + f.stepless() + "/evidence").file(file).with(who)).andExpect(status().isForbidden());
            mvc.perform(post(run + "/retest").with(who)).andExpect(status().isForbidden());
        }
        assertThat(mgmt.getRun(f.id()).cases().get(0).steps().get(0).evidence()).isEmpty();
        mvc.perform(multipart(run + "/steps/" + f.step(0) + "/evidence").file(file)).andExpect(status().isUnauthorized());

        mvc.perform(multipart(run + "/steps/" + f.step(0) + "/evidence").file(file).with(tester))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.cases[0].steps[0].evidence[0].requirementKey").value(f.requirement().getKey()))
            .andExpect(jsonPath("$.cases[0].steps[0].evidence[0].version").value(1));
        mvc.perform(multipart(run + "/cases/" + f.stepless() + "/evidence").file(file).param("requirementId", f.requirement().getId().toString()).with(tester))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.cases[1].evidence[0].filename").exists());
        mvc.perform(multipart(run + "/steps/" + f.step(1) + "/evidence").file(new MockMultipartFile("file", "x.exe", "application/octet-stream", PNG)).with(tester))
            .andExpect(status().is4xxClientError());
        mvc.perform(post(run + "/retest").with(tester)).andExpect(status().isConflict()); // not completed yet

        complete(f, "PASS");
        mvc.perform(post(run + "/retest").with(tester).contentType("application/json").content("{\"buildLabel\":\"b2\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.run.retestOf").value(f.id().toString()))
            .andExpect(jsonPath("$.run.status").value("PLANNED"))
            .andExpect(jsonPath("$.run.buildLabel").value("b2"))
            .andExpect(jsonPath("$.cases.length()").value(1));
    }
}
