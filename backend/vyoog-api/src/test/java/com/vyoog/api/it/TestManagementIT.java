package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.evidence.TestCase;
import com.vyoog.evidence.TestCaseService;
import com.vyoog.evidence.TestManagementService;
import com.vyoog.evidence.TestManagementService.StepInput;
import com.vyoog.evidence.VerificationResult;
import com.vyoog.evidence.VerificationService;
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
 * VYB-0923 (F14): test plans, suites, structured steps and run creation, against a real PostgreSQL
 * with every migration applied. Per-step results are VYB-0924, so nothing here records a result.
 */
@AutoConfigureMockMvc
class TestManagementIT extends IntegrationTestBase {

    @Autowired TestManagementService mgmt;
    @Autowired TestCaseService testCases;
    @Autowired VerificationService verification;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private TestCase newCase(Portfolio p, UUID actor, String description) {
        Requirement r = newRequirement(p, actor);
        return testCases.draft(unique("Case"), description, null, r.getId(), actor);
    }

    private TestManagementService.Suite suiteWith(UUID app, UUID actor, TestCase... cases) {
        TestManagementService.Plan plan = mgmt.createPlan(unique("Plan"), null, app, null, actor);
        TestManagementService.Suite suite = mgmt.createSuite(plan.id(), unique("Suite"), null, actor);
        mgmt.setSuiteCases(suite.id(), java.util.Arrays.stream(cases).map(TestCase::getId).toList(), actor);
        return mgmt.getSuite(suite.id());
    }

    // ------------------------------------------------------------------ plans

    @Test
    void VYB0923_AC1_aPlanBelongsToAnApplicationGetsAKeyAndMayHaveARelease() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("qa");
        UUID release = jdbc.queryForObject("INSERT INTO release (name) VALUES (?) RETURNING id", UUID.class, unique("R"));

        TestManagementService.Plan plan = mgmt.createPlan("  Regression  ", "  all of it ", p.applicationId(), release, actor);
        TestManagementService.Plan bare = mgmt.createPlan(unique("Smoke"), "", p.applicationId(), null, actor);

        assertThat(plan.key()).matches("TP-\\d+");
        assertThat(plan.name()).isEqualTo("Regression");
        assertThat(plan.description()).isEqualTo("all of it");
        assertThat(plan.releaseId()).isEqualTo(release);
        assertThat(bare.releaseId()).isNull();
        assertThat(bare.description()).isNull();
        assertThat(bare.key()).isNotEqualTo(plan.key());
        assertThat(mgmt.listPlans(p.applicationId(), null)).extracting(TestManagementService.Plan::id)
            .containsExactlyInAnyOrder(plan.id(), bare.id());
        assertThat(mgmt.listPlans(p.applicationId(), release)).extracting(TestManagementService.Plan::id).containsExactly(plan.id());
        assertThat(auditCount(plan.id(), "test-plan.created")).isEqualTo(1);
    }

    @Test
    void VYB0923_AC1_aPlanNeedsANameAnExistingApplicationAndAnExistingRelease() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("qa");
        assertThatThrownBy(() -> mgmt.createPlan("  ", null, p.applicationId(), null, actor)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mgmt.createPlan("x", null, UUID.randomUUID(), null, actor)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> mgmt.createPlan("x", null, null, null, actor)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mgmt.createPlan("x", null, p.applicationId(), UUID.randomUUID(), actor))
            .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void VYB0923_AC1_aPlanCanBeRenamedAndMovedToAnotherRelease() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("qa");
        TestManagementService.Plan plan = mgmt.createPlan(unique("Plan"), null, p.applicationId(), null, actor);
        UUID release = jdbc.queryForObject("INSERT INTO release (name) VALUES (?) RETURNING id", UUID.class, unique("R"));

        TestManagementService.Plan updated = mgmt.updatePlan(plan.id(), "Renamed", "d", release, actor);

        assertThat(updated.name()).isEqualTo("Renamed");
        assertThat(updated.releaseId()).isEqualTo(release);
        assertThat(updated.key()).isEqualTo(plan.key());
        assertThat(auditCount(plan.id(), "test-plan.updated")).isEqualTo(1);
    }

    // ----------------------------------------------------------------- suites

    @Test
    void VYB0923_AC2_suitesAreNamedOrderedAndUniqueWithinTheirPlan() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("qa");
        TestManagementService.Plan plan = mgmt.createPlan(unique("Plan"), null, p.applicationId(), null, actor);
        TestManagementService.Plan other = mgmt.createPlan(unique("Plan"), null, p.applicationId(), null, actor);

        TestManagementService.Suite a = mgmt.createSuite(plan.id(), "Login", null, actor);
        TestManagementService.Suite b = mgmt.createSuite(plan.id(), "Checkout", "pay", actor);

        assertThat(a.position()).isEqualTo(1);
        assertThat(b.position()).isEqualTo(2);
        assertThat(mgmt.listSuites(plan.id())).extracting(TestManagementService.Suite::name).containsExactly("Login", "Checkout");
        assertThatThrownBy(() -> mgmt.createSuite(plan.id(), "Login", null, actor)).isInstanceOf(IllegalStateException.class);
        assertThat(mgmt.createSuite(other.id(), "Login", null, actor).name()).isEqualTo("Login"); // another plan: fine
        assertThatThrownBy(() -> mgmt.updateSuite(b.id(), "Login", null, actor)).isInstanceOf(IllegalStateException.class);
        assertThat(mgmt.updateSuite(b.id(), "Checkout v2", "pay", actor).name()).isEqualTo("Checkout v2");
        assertThat(auditCount(a.id(), "test-suite.created")).isEqualTo(1);
        assertThat(auditCount(b.id(), "test-suite.updated")).isEqualTo(1);
    }

    @Test
    void VYB0923_AC2_aSuiteIsAnOrderedGroupOfExistingTestCasesReplacedAsAWhole() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("qa");
        TestCase one = newCase(p, actor, null), two = newCase(p, actor, null), three = newCase(p, actor, null);
        TestManagementService.Suite suite = suiteWith(p.applicationId(), actor);

        List<TestManagementService.SuiteCase> first = mgmt.setSuiteCases(suite.id(), List.of(two.getId(), one.getId(), three.getId()), actor);
        assertThat(first).extracting(TestManagementService.SuiteCase::key).containsExactly(two.getKey(), one.getKey(), three.getKey());
        assertThat(first).extracting(TestManagementService.SuiteCase::position).containsExactly(1, 2, 3);

        List<TestManagementService.SuiteCase> second = mgmt.setSuiteCases(suite.id(), List.of(three.getId(), two.getId()), actor);
        assertThat(second).extracting(TestManagementService.SuiteCase::key).containsExactly(three.getKey(), two.getKey());
        assertThat(mgmt.getSuite(suite.id()).caseCount()).isEqualTo(2);
        assertThat(mgmt.setSuiteCases(suite.id(), List.of(), actor)).isEmpty();
        assertThat(auditCount(suite.id(), "test-suite.cases-set")).isEqualTo(4); // the helper's own call plus these three
    }

    @Test
    void VYB0923_AC2_aCaseCannotBeInASuiteTwiceAndMustExist() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("qa");
        TestCase one = newCase(p, actor, null);
        TestManagementService.Suite suite = suiteWith(p.applicationId(), actor, one);

        assertThatThrownBy(() -> mgmt.setSuiteCases(suite.id(), List.of(one.getId(), one.getId()), actor))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mgmt.setSuiteCases(suite.id(), List.of(UUID.randomUUID()), actor))
            .isInstanceOf(NoSuchElementException.class);
        assertThat(mgmt.suiteCases(suite.id())).hasSize(1); // a refused replacement left the suite as it was
    }

    // ------------------------------------------------------------------ steps

    @Test
    void VYB0923_AC3_aCaseHasOrderedStepsEachWithAnActionAndAnExpectedResultBesideItsDescription() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("qa");
        TestCase tc = newCase(p, actor, "free text stays as it is");

        List<TestManagementService.Step> steps = mgmt.setSteps(tc.getId(), List.of(
            new StepInput(" Open the login page ", "The form is shown"),
            new StepInput("Submit a wrong password", "An error is shown")), actor);

        assertThat(steps).extracting(TestManagementService.Step::position).containsExactly(1, 2);
        assertThat(steps.get(0).action()).isEqualTo("Open the login page");
        assertThat(steps.get(1).expectedResult()).isEqualTo("An error is shown");
        assertThat(jdbc.queryForObject("SELECT description FROM test_case WHERE id = ?", String.class, tc.getId()))
            .isEqualTo("free text stays as it is");
        assertThat(auditCount(tc.getId(), "test-step.set")).isEqualTo(1);

        assertThat(mgmt.setSteps(tc.getId(), List.of(new StepInput("only", "one")), actor)).hasSize(1); // replaced, not appended
        assertThat(mgmt.setSteps(tc.getId(), List.of(), actor)).isEmpty();
    }

    @Test
    void VYB0923_AC3_aStepWithNoActionOrNoExpectedResultIsRefusedAndNothingIsChanged() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("qa");
        TestCase tc = newCase(p, actor, null);
        mgmt.setSteps(tc.getId(), List.of(new StepInput("keep", "me")), actor);

        assertThatThrownBy(() -> mgmt.setSteps(tc.getId(), List.of(new StepInput("ok", "ok"), new StepInput("  ", "x")), actor))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Step 2 needs an action");
        assertThatThrownBy(() -> mgmt.setSteps(tc.getId(), List.of(new StepInput("act", null)), actor))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expected result");
        assertThat(mgmt.steps(tc.getId())).extracting(TestManagementService.Step::action).containsExactly("keep");
        assertThatThrownBy(() -> mgmt.setSteps(UUID.randomUUID(), List.of(), actor)).isInstanceOf(NoSuchElementException.class);
    }

    // ------------------------------------------------------------------- runs

    @Test
    void VYB0923_AC4_creatingARunCopiesTheSuitesCasesAndStepsIntoAPlannedManualRun() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("qa"), assignee = newUser("tester");
        TestCase one = newCase(p, actor, "first"), two = newCase(p, actor, null);
        mgmt.setSteps(one.getId(), List.of(new StepInput("a1", "e1"), new StepInput("a2", "e2")), actor);
        TestManagementService.Suite suite = suiteWith(p.applicationId(), actor, two, one);

        TestManagementService.RunDetail run = mgmt.createRun(suite.id(), assignee, " build-7 ", actor);

        assertThat(run.run().kind()).isEqualTo("MANUAL");
        assertThat(run.run().status()).isEqualTo("PLANNED");
        assertThat(run.run().startedAt()).isNull();
        assertThat(run.run().completedAt()).isNull();
        assertThat(run.run().assignedTo()).isEqualTo(assignee);
        assertThat(run.run().buildLabel()).isEqualTo("build-7");
        assertThat(run.run().suiteId()).isEqualTo(suite.id());
        assertThat(run.cases()).extracting(TestManagementService.RunCase::key).containsExactly(two.getKey(), one.getKey());
        assertThat(run.cases().get(0).steps()).isEmpty();
        assertThat(run.cases().get(1).description()).isEqualTo("first");
        assertThat(run.cases().get(1).steps()).extracting(TestManagementService.RunStep::action).containsExactly("a1", "a2");
        assertThat(run.cases().get(1).steps()).extracting(TestManagementService.RunStep::expectedResult).containsExactly("e1", "e2");
        assertThat(auditCount(run.run().id(), "test-run.created")).isEqualTo(1);
        assertThat(mgmt.listRuns(suite.id(), null, "PLANNED")).extracting(TestManagementService.Run::id).containsExactly(run.run().id());
        assertThat(mgmt.listRuns(null, suite.planId(), "COMPLETED")).isEmpty();
    }

    @Test
    void VYB0923_AC4_theRunKeepsWhatWasPlannedWhenTheCaseItsStepsOrTheSuiteChangeLater() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("qa");
        TestCase one = newCase(p, actor, "as planned"), two = newCase(p, actor, null);
        mgmt.setSteps(one.getId(), List.of(new StepInput("old action", "old result")), actor);
        TestManagementService.Suite suite = suiteWith(p.applicationId(), actor, one, two);
        TestManagementService.RunDetail run = mgmt.createRun(suite.id(), null, null, actor);

        testCases.update(one.getId(), "Renamed afterwards", "rewritten", null, actor);
        mgmt.setSteps(one.getId(), List.of(new StepInput("new action", "new result"), new StepInput("more", "more")), actor);
        mgmt.setSuiteCases(suite.id(), List.of(two.getId()), actor);
        jdbc.update("DELETE FROM test_case WHERE id = ?", one.getId()); // the live case disappears altogether

        TestManagementService.RunDetail again = mgmt.getRun(run.run().id());
        assertThat(again.cases()).hasSize(2);
        assertThat(again.cases().get(0).title()).isEqualTo(one.getTitle());
        assertThat(again.cases().get(0).description()).isEqualTo("as planned");
        assertThat(again.cases().get(0).steps()).extracting(TestManagementService.RunStep::action).containsExactly("old action");
        assertThat(again.cases().get(1).key()).isEqualTo(two.getKey());
        // and a run created now sees the current state, not the old one
        TestManagementService.RunDetail next = mgmt.createRun(suite.id(), null, null, actor);
        assertThat(next.cases()).extracting(TestManagementService.RunCase::key).containsExactly(two.getKey());
    }

    @Test
    void VYB0923_AC4_aSuiteWithNoCasesCannotBeRunAndAnAssigneeMustExist() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("qa");
        TestManagementService.Suite empty = suiteWith(p.applicationId(), actor);
        assertThatThrownBy(() -> mgmt.createRun(empty.id(), null, null, actor)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no test cases");
        TestManagementService.Suite full = suiteWith(p.applicationId(), actor, newCase(p, actor, null));
        assertThatThrownBy(() -> mgmt.createRun(full.id(), UUID.randomUUID(), null, actor)).isInstanceOf(NoSuchElementException.class);
        assertThat(mgmt.listRuns(full.id(), null, null)).isEmpty(); // the refused call left no half-made run
        assertThatThrownBy(() -> mgmt.listRuns(null, null, "DONE")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void VYB0923_AC5_ciIngestionIsUntouchedACiRunStaysCompletedAndANewManualRunNeverChangesARequirement() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("qa");
        Requirement r = newRequirement(p, actor);
        String before = jdbc.queryForObject("SELECT status FROM requirement WHERE id = ?", String.class, r.getId());

        com.vyoog.evidence.TestRun ci = verification.findOrCreateRun(unique("build"), "github-actions");
        verification.ingest(ci, new VerificationService.ResultInput(unique("T"), "ci test", VerificationResult.PASS, List.of(r.getKey())));
        var row = jdbc.queryForMap("SELECT kind, status, suite_id, started_at, completed_at FROM test_run WHERE id = ?", ci.getId());
        assertThat(row).containsEntry("kind", "CI").containsEntry("status", "COMPLETED").containsEntry("suite_id", null);
        assertThat(row.get("started_at")).isNotNull();

        TestCase tc = testCases.draft(unique("Case"), null, null, r.getId(), actor);
        TestManagementService.Suite suite = suiteWith(p.applicationId(), actor, tc);
        UUID manual = mgmt.createRun(suite.id(), null, null, actor).run().id();

        assertThat(mgmt.listRuns(null, null, null)).extracting(TestManagementService.Run::id).contains(manual).doesNotContain(ci.getId());
        assertThat(jdbc.queryForObject("SELECT status FROM requirement WHERE id = ?", String.class, r.getId())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM verification WHERE test_run_id = ?", Integer.class, manual)).isZero();
    }

    @Test
    void VYB0923_AC5_theDatabaseRefusesAManualRunWithNoSuiteAndACiRunThatIsNotCompleted() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO test_run (kind, status) VALUES ('MANUAL', 'PLANNED')"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO test_run (kind, status) VALUES ('CI', 'PLANNED')"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO test_step (test_case_id, position, action, expected_result) "
            + "SELECT id, 1, 'a', ' ' FROM test_case LIMIT 1")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // --------------------------------------------------------------- deletion

    @Test
    void VYB0923_AC6_aPlanOrSuiteThatHasBeenRunCannotBeDeletedOneThatHasNotCan() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("qa");
        TestManagementService.Suite used = suiteWith(p.applicationId(), actor, newCase(p, actor, null));
        mgmt.createRun(used.id(), null, null, actor);

        assertThatThrownBy(() -> mgmt.deleteSuite(used.id(), actor)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> mgmt.deletePlan(used.planId(), actor)).isInstanceOf(IllegalStateException.class);
        assertThat(mgmt.getSuite(used.id())).isNotNull();

        TestManagementService.Plan plan = mgmt.createPlan(unique("Plan"), null, p.applicationId(), null, actor);
        TestManagementService.Suite suite = mgmt.createSuite(plan.id(), "gone", null, actor);
        mgmt.deleteSuite(suite.id(), actor);
        mgmt.deletePlan(plan.id(), actor);
        assertThatThrownBy(() -> mgmt.getPlan(plan.id())).isInstanceOf(NoSuchElementException.class);
        assertThat(auditCount(suite.id(), "test-suite.deleted")).isEqualTo(1);
        assertThat(auditCount(plan.id(), "test-plan.deleted")).isEqualTo(1);
    }

    // ------------------------------------------------------------------- HTTP

    private JwtRequestPostProcessor signedInAs(String id) {
        return jwt().jwt(j -> j.subject("sub-" + id).claim("email", id + "@it.test").claim("preferred_username", id).claim("azp", "vyoog-web"));
    }

    private JwtRequestPostProcessor aPersonWith(AccessRole role, UUID capabilityId) {
        String id = unique("p");
        UUID user = users.upsert("sub-" + id, id + "@it.test", id).getId();
        grantOnCapability(user, role, capabilityId);
        return signedInAs(id);
    }

    private String create(JwtRequestPostProcessor who, String url, String body) throws Exception {
        return json.readTree(mvc.perform(post(url).with(who).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void VYB0923_AC7_everyWriteIsRefusedWith403ToAPersonWhoIsNotATesterAndReadsAreOpenToAnySignedInPerson() throws Exception {
        Portfolio p = newPortfolio();
        JwtRequestPostProcessor tester = aPersonWith(AccessRole.TESTER, p.capabilityId());
        JwtRequestPostProcessor analyst = aPersonWith(AccessRole.BUSINESS_ANALYST, p.capabilityId());
        JwtRequestPostProcessor viewer = aPersonWith(AccessRole.VIEWER, p.capabilityId());
        UUID actor = newUser("qa");
        TestCase tc = newCase(p, actor, null);

        String planId = create(tester, "/api/v1/test-plans", "{\"name\":\"P\",\"applicationId\":\"" + p.applicationId() + "\"}");
        String suiteId = create(tester, "/api/v1/test-plans/" + planId + "/suites", "{\"name\":\"S\"}");

        for (JwtRequestPostProcessor who : List.of(analyst, viewer)) {
            mvc.perform(post("/api/v1/test-plans").with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"P\",\"applicationId\":\"" + p.applicationId() + "\"}")).andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/test-plans/" + planId).with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"P2\"}")).andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/test-plans/" + planId).with(who)).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/test-plans/" + planId + "/suites").with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"S2\"}")).andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/test-suites/" + suiteId).with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"S3\"}")).andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/test-suites/" + suiteId).with(who)).andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/test-suites/" + suiteId + "/cases").with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"testCaseIds\":[\"" + tc.getId() + "\"]}")).andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/test-cases/" + tc.getId() + "/steps").with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"steps\":[]}")).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/test-suites/" + suiteId + "/runs").with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/test-plans/" + planId).with(viewer)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/test-plans").with(viewer)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/test-plans/" + planId + "/suites").with(viewer)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/test-runs").with(viewer)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/test-plans")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_suite WHERE plan_id = ?::uuid", Integer.class, planId)).isEqualTo(1);
    }

    @Test
    void VYB0923_AC7_aTesterCanPlanAndCreateARunThroughTheApiAndSeeTheSnapshot() throws Exception {
        Portfolio p = newPortfolio();
        JwtRequestPostProcessor tester = aPersonWith(AccessRole.TESTER, p.capabilityId());
        UUID actor = newUser("qa");
        TestCase tc = newCase(p, actor, null);

        String planId = create(tester, "/api/v1/test-plans", "{\"name\":\"P\",\"applicationId\":\"" + p.applicationId() + "\"}");
        String suiteId = create(tester, "/api/v1/test-plans/" + planId + "/suites", "{\"name\":\"S\"}");
        mvc.perform(put("/api/v1/test-cases/" + tc.getId() + "/steps").with(tester).contentType(MediaType.APPLICATION_JSON)
            .content("{\"steps\":[{\"action\":\"do\",\"expectedResult\":\"done\"}]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].position").value(1));
        mvc.perform(put("/api/v1/test-suites/" + suiteId + "/cases").with(tester).contentType(MediaType.APPLICATION_JSON)
            .content("{\"testCaseIds\":[\"" + tc.getId() + "\"]}")).andExpect(status().isOk()).andExpect(jsonPath("$[0].key").value(tc.getKey()));
        String runId = json.readTree(mvc.perform(post("/api/v1/test-suites/" + suiteId + "/runs").with(tester)
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString()).get("run").get("id").asText();

        mvc.perform(get("/api/v1/test-runs/" + runId).with(tester))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.run.status").value("PLANNED"))
            .andExpect(jsonPath("$.run.kind").value("MANUAL"))
            .andExpect(jsonPath("$.cases[0].steps[0].expectedResult").value("done"));
        mvc.perform(get("/api/v1/test-plans/" + planId).with(tester)).andExpect(jsonPath("$.runCount").value(1));
        mvc.perform(delete("/api/v1/test-plans/" + planId).with(tester)).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/test-plans").with(tester).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
            .andExpect(status().is4xxClientError());
        mvc.perform(get("/api/v1/test-plans/" + UUID.randomUUID()).with(tester)).andExpect(status().isNotFound());
    }
}
