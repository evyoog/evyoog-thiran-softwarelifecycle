package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.design.DesignNode;
import com.vyoog.design.DesignService;
import com.vyoog.design.NodeKind;
import com.vyoog.evidence.TestCase;
import com.vyoog.evidence.TestCaseService;
import com.vyoog.evidence.TestExecutionService;
import com.vyoog.evidence.TestManagementService;
import com.vyoog.evidence.VerificationResult;
import com.vyoog.evidence.VerificationService;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * VYB-0918b (D24, D52): the other three things the Agile Planner is told about a requirement it sent: a design artifact that now covers it,
 * a test case that now verifies it, and each test result recorded against it. What is queued, with the shapes the Planner gets and the
 * order they go in; the sending itself is {@link PlannerWebhookIT}. A requirement the Planner did not send queues none of them.
 */
class PlannerEventsIT extends IntegrationTestBase {

    @Autowired DesignService design;
    @Autowired TestCaseService testCases;
    @Autowired VerificationService verification;
    @Autowired TestManagementService mgmt;
    @Autowired TestExecutionService exec;
    @Autowired ObjectMapper json;

    @BeforeEach
    void emptyQueue() {
        jdbc.update("DELETE FROM planner_outbound_event"); // the queue is global
    }

    private Requirement fromPlanner(Portfolio p, UUID author) {
        Requirement r = newRequirement(p, author);
        jdbc.update("INSERT INTO planner_function_link (source_function_id, requirement_id, content_hash) VALUES (?, ?, 'x')", unique("FN"), r.getId());
        return r;
    }

    private Requirement submit(Requirement r, UUID author) {
        return requirementService.transition(r.getId(), r.getRevision(), RequirementStatus.IN_REVIEW, "no criteria yet, submitted on purpose", author);
    }

    private List<Map<String, Object>> events(Requirement r, String type) {
        return jdbc.queryForList("SELECT event_id, version, payload::text AS payload FROM planner_outbound_event "
            + "WHERE requirement_id = ? AND event_type = ? ORDER BY version", r.getId(), type);
    }

    private JsonNode body(Map<String, Object> event) throws Exception {
        return json.readTree((String) event.get("payload"));
    }

    private long version(Map<String, Object> event) {
        return ((Number) event.get("version")).longValue();
    }

    @Test
    void VYB0918_AC10_aDesignArtifactThatCoversARequirementIsReportedOnceAndNeverForThePipelineMilestones() throws Exception {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        Requirement r = submit(fromPlanner(p, author), author);
        var flow = design.createFlow(p.applicationId(), author);

        design.generateFrom(flow.getId(), p.applicationId(), author);
        List<Map<String, Object>> made = events(r, "design_artifact.created");
        assertThat(made).as("one for the node drawn for it; the Testing and Deployment milestones also cover it but are not design artifacts").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_node_requirement WHERE requirement_id = ?", Integer.class, r.getId()))
            .as("the milestones do cover it").isEqualTo(3);
        JsonNode b = body(made.get(0));
        assertThat(b.get("event").asText()).isEqualTo("design_artifact.created");
        assertThat(b.get("requirement_id").asText()).isEqualTo(r.getKey());
        assertThat(b.get("source_function_id").asText()).startsWith("FN-");
        assertThat(b.get("design_artifact_id").asText()).isNotBlank();
        assertThat(b.get("kind").asText()).isNotBlank();
        assertThat(b.get("name").asText()).startsWith(r.getKey());

        design.generateFrom(flow.getId(), p.applicationId(), author);
        assertThat(events(r, "design_artifact.created")).as("drawing it again adds nothing").hasSize(1);

        DesignNode extra = design.addNode(flow.getId(), NodeKind.step, "Pause screen", null, author);
        design.linkRequirement(extra.getId(), r.getId(), author);
        design.linkRequirement(extra.getId(), r.getId(), author);
        assertThat(events(r, "design_artifact.created")).as("a node linked by hand is reported, once").hasSize(2);

        design.unlinkRequirement(extra.getId(), r.getId(), author);
        design.linkRequirement(extra.getId(), r.getId(), author);
        assertThat(events(r, "design_artifact.created")).as("unlinking and linking it again is not a new artifact").hasSize(2);

        DesignNode milestone = design.addNode(flow.getId(), NodeKind.testing, "My own testing stage", null, author);
        design.linkRequirement(milestone.getId(), r.getId(), author);
        assertThat(events(r, "design_artifact.created")).as("a testing or deployment node is not a design artifact").hasSize(2);
    }

    @Test
    void VYB0918_AC11_aRequirementThePlannerDidNotSendQueuesNoneOfTheThree() {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        Requirement ours = submit(newRequirement(p, author), author);
        var flow = design.createFlow(p.applicationId(), author);
        design.generateFrom(flow.getId(), p.applicationId(), author);
        TestCase t = testCases.draft(unique("Ours"), null, null, ours.getId(), author);
        var run = verification.findOrCreateRun(unique("build"), "ci");
        verification.ingest(run, new VerificationService.ResultInput(t.getKey(), null, VerificationResult.PASS, List.of(ours.getKey())));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM planner_outbound_event WHERE requirement_id = ?", Integer.class, ours.getId())).isZero();
    }

    @Test
    void VYB0918_AC12_aTestCaseThatNowVerifiesARequirementIsReportedOnceWithItsKeyAndTitle() throws Exception {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        Requirement r = fromPlanner(p, author);
        TestCase t = testCases.draft("Pausing stops billing", "details", null, r.getId(), author);

        List<Map<String, Object>> made = events(r, "test_case.created");
        assertThat(made).hasSize(1);
        JsonNode b = body(made.get(0));
        assertThat(b.get("event").asText()).isEqualTo("test_case.created");
        assertThat(b.get("test_case_id").asText()).isEqualTo(t.getKey());
        assertThat(b.get("title").asText()).isEqualTo("Pausing stops billing");
        assertThat(b.get("requirement_id").asText()).isEqualTo(r.getKey());

        // the same test case verifying a second Planner requirement is a second event, about that requirement
        Requirement other = fromPlanner(p, author);
        verification.ingest(verification.findOrCreateRun(unique("build"), "ci"),
            new VerificationService.ResultInput(t.getKey(), null, VerificationResult.PASS, List.of(other.getKey())));
        assertThat(events(other, "test_case.created")).hasSize(1);
        assertThat(events(r, "test_case.created")).hasSize(1);
    }

    @Test
    void VYB0918_AC13_aCiResultIsReportedWithItsOutcomeBuildAndRevisionAfterItsTestCaseAndOnlyOncePerRun() throws Exception {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        Requirement r = fromPlanner(p, author);
        String testKey = unique("T");
        String build = unique("build");
        var run = verification.findOrCreateRun(build, "ci");

        verification.ingest(run, new VerificationService.ResultInput(testKey, "A test the CI knew first", VerificationResult.PASS, List.of(r.getKey())));
        List<Map<String, Object>> cases = events(r, "test_case.created");
        List<Map<String, Object>> results = events(r, "test_result.recorded");
        assertThat(cases).hasSize(1);
        assertThat(results).hasSize(1);
        assertThat(version(cases.get(0))).as("the test case is announced before its result").isLessThan(version(results.get(0)));
        JsonNode b = body(results.get(0));
        assertThat(b.get("event").asText()).isEqualTo("test_result.recorded");
        assertThat(b.get("test_case_id").asText()).isEqualTo(testKey);
        assertThat(b.get("outcome").asText()).isEqualTo("pass");
        assertThat(b.get("build_reference").asText()).isEqualTo(build);
        assertThat(b.get("requirement_revision").asInt()).isEqualTo(r.getRevision());

        verification.ingest(run, new VerificationService.ResultInput(testKey, null, VerificationResult.PASS, List.of(r.getKey())));
        assertThat(events(r, "test_result.recorded")).as("the same result for the same run is not reported twice").hasSize(1);

        var later = verification.findOrCreateRun(unique("build"), "ci");
        verification.ingest(later, new VerificationService.ResultInput(testKey, null, VerificationResult.FAIL, List.of(r.getKey())));
        assertThat(events(r, "test_case.created")).as("the test case is not announced again").hasSize(1);
        List<Map<String, Object>> after = events(r, "test_result.recorded");
        assertThat(after).hasSize(2);
        assertThat(body(after.get(1)).get("outcome").asText()).isEqualTo("fail");
        assertThat(version(after.get(1))).isGreaterThan(version(after.get(0)));
    }

    @Test
    void VYB0918_AC14_aManualRunReportsEachCaseResultWhenItIsCompletedABlockedOneIncluded() throws Exception {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        UUID tester = newUser("tester");
        Requirement r = fromPlanner(p, author);
        TestCase passing = testCases.draft(unique("P"), null, null, r.getId(), tester);
        TestCase failing = testCases.draft(unique("F"), null, null, r.getId(), tester);
        TestCase blocked = testCases.draft(unique("B"), null, null, r.getId(), tester);
        var plan = mgmt.createPlan(unique("Plan"), null, p.applicationId(), null, tester);
        var suite = mgmt.createSuite(plan.id(), unique("Suite"), null, tester);
        mgmt.setSuiteCases(suite.id(), List.of(passing.getId(), failing.getId(), blocked.getId()), tester);
        String label = unique("manual-build");
        var run = mgmt.createRun(suite.id(), null, label, tester);
        exec.start(run.run().id(), tester);
        exec.recordCaseResult(run.run().id(), run.cases().get(0).id(), "PASS", null, tester);
        exec.recordCaseResult(run.run().id(), run.cases().get(1).id(), "FAIL", "Saw a 500 page", tester);
        exec.recordCaseResult(run.run().id(), run.cases().get(2).id(), "BLOCKED", "The test environment was down", tester);
        assertThat(events(r, "test_result.recorded")).as("nothing is reported while the run is still being filled in").isEmpty();

        exec.complete(run.run().id(), tester);

        List<Map<String, Object>> results = events(r, "test_result.recorded");
        assertThat(results).hasSize(3);
        Map<String, String> outcomeByCase = new java.util.HashMap<>();
        for (Map<String, Object> e : results) {
            JsonNode b = body(e);
            outcomeByCase.put(b.get("test_case_id").asText(), b.get("outcome").asText());
            assertThat(b.get("build_reference").asText()).isEqualTo(label);
        }
        assertThat(outcomeByCase).containsEntry(passing.getKey(), "pass").containsEntry(failing.getKey(), "fail").containsEntry(blocked.getKey(), "blocked");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM verification WHERE requirement_id = ?", Integer.class, r.getId()))
            .as("a blocked case still writes no verification: that is unchanged").isEqualTo(2);
        assertThat(events(r, "test_case.created")).as("the three test cases were announced when they were drafted").hasSize(3);
    }
}
