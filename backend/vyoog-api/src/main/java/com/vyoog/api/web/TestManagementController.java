package com.vyoog.api.web;

import com.vyoog.api.config.RequiresAccess;
import com.vyoog.evidence.TestExecutionService;
import com.vyoog.evidence.TestManagementService;
import com.vyoog.identity.AccessRule;
import com.vyoog.identity.UserProvisioningService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * VYB-0923: test plans, suites, structured steps and run creation. Reads are open to any
 * signed-in person, like test cases; every write is QA work — the matrix's "Verify" column,
 * the same rule test cases use ({@code TestCaseController}). The matrix has no column of its
 * own for test planning; Verify is the nearest, and the mapping is recorded in
 * {@code docs/08-architecture/security/access-rules.md}.
 */
@RestController
@RequestMapping("/api/v1")
public class TestManagementController {

    private final TestManagementService service;
    private final TestExecutionService execution;
    private final UserProvisioningService provisioning;

    public TestManagementController(TestManagementService service, TestExecutionService execution,
                                     UserProvisioningService provisioning) {
        this.service = service;
        this.execution = execution;
        this.provisioning = provisioning;
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    // ------------------------------------------------------------------ plans

    public record CreateTestPlan(@NotBlank String name, String description, @NotNull UUID applicationId, UUID releaseId) {}

    public record UpdateTestPlan(@NotBlank String name, String description, UUID releaseId) {}

    public record TestPlanView(String id, String key, String name, String description, String applicationId,
                                String releaseId, String createdAt, long suiteCount, long runCount) {}

    private static TestPlanView view(TestManagementService.Plan p) {
        return new TestPlanView(p.id().toString(), p.key(), p.name(), p.description(), p.applicationId().toString(),
            p.releaseId() == null ? null : p.releaseId().toString(), p.createdAt().toString(), p.suiteCount(), p.runCount());
    }

    @RequiresAccess(value = AccessRule.VERIFY, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping("/test-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public TestPlanView createPlan(@RequestBody CreateTestPlan body, @AuthenticationPrincipal Jwt jwt) {
        return view(service.createPlan(body.name(), body.description(), body.applicationId(), body.releaseId(), currentUserId(jwt)));
    }

    @GetMapping("/test-plans")
    public List<TestPlanView> listPlans(@RequestParam(required = false) UUID applicationId,
                                         @RequestParam(required = false) UUID releaseId) {
        return service.listPlans(applicationId, releaseId).stream().map(TestManagementController::view).toList();
    }

    @GetMapping("/test-plans/{id}")
    public TestPlanView getPlan(@PathVariable UUID id) {
        return view(service.getPlan(id));
    }

    @RequiresAccess(value = AccessRule.VERIFY, scope = RequiresAccess.Scope.ANYWHERE)
    @PutMapping("/test-plans/{id}")
    public TestPlanView updatePlan(@PathVariable UUID id, @RequestBody UpdateTestPlan body, @AuthenticationPrincipal Jwt jwt) {
        return view(service.updatePlan(id, body.name(), body.description(), body.releaseId(), currentUserId(jwt)));
    }

    @RequiresAccess(value = AccessRule.VERIFY, scope = RequiresAccess.Scope.ANYWHERE)
    @DeleteMapping("/test-plans/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePlan(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        service.deletePlan(id, currentUserId(jwt));
    }

    // ----------------------------------------------------------------- suites

    public record CreateTestSuite(@NotBlank String name, String description) {}

    public record TestSuiteView(String id, String planId, String name, String description, int position, long caseCount) {}

    public record SuiteCaseView(String testCaseId, String key, String title, int position) {}

    public record SetSuiteCases(List<UUID> testCaseIds) {}

    private static TestSuiteView view(TestManagementService.Suite s) {
        return new TestSuiteView(s.id().toString(), s.planId().toString(), s.name(), s.description(), s.position(), s.caseCount());
    }

    private static List<SuiteCaseView> caseViews(List<TestManagementService.SuiteCase> cases) {
        return cases.stream().map(c -> new SuiteCaseView(c.testCaseId().toString(), c.key(), c.title(), c.position())).toList();
    }

    @RequiresAccess(value = AccessRule.VERIFY, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping("/test-plans/{planId}/suites")
    @ResponseStatus(HttpStatus.CREATED)
    public TestSuiteView createSuite(@PathVariable UUID planId, @RequestBody CreateTestSuite body, @AuthenticationPrincipal Jwt jwt) {
        return view(service.createSuite(planId, body.name(), body.description(), currentUserId(jwt)));
    }

    @GetMapping("/test-plans/{planId}/suites")
    public List<TestSuiteView> listSuites(@PathVariable UUID planId) {
        return service.listSuites(planId).stream().map(TestManagementController::view).toList();
    }

    @GetMapping("/test-suites/{id}")
    public TestSuiteView getSuite(@PathVariable UUID id) {
        return view(service.getSuite(id));
    }

    @RequiresAccess(value = AccessRule.VERIFY, scope = RequiresAccess.Scope.ANYWHERE)
    @PutMapping("/test-suites/{id}")
    public TestSuiteView updateSuite(@PathVariable UUID id, @RequestBody CreateTestSuite body, @AuthenticationPrincipal Jwt jwt) {
        return view(service.updateSuite(id, body.name(), body.description(), currentUserId(jwt)));
    }

    @RequiresAccess(value = AccessRule.VERIFY, scope = RequiresAccess.Scope.ANYWHERE)
    @DeleteMapping("/test-suites/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSuite(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        service.deleteSuite(id, currentUserId(jwt));
    }

    @GetMapping("/test-suites/{id}/cases")
    public List<SuiteCaseView> suiteCases(@PathVariable UUID id) {
        return caseViews(service.suiteCases(id));
    }

    /** Replaces the suite's cases with exactly this ordered list. */
    @RequiresAccess(value = AccessRule.VERIFY, scope = RequiresAccess.Scope.ANYWHERE)
    @PutMapping("/test-suites/{id}/cases")
    public List<SuiteCaseView> setSuiteCases(@PathVariable UUID id, @RequestBody SetSuiteCases body, @AuthenticationPrincipal Jwt jwt) {
        return caseViews(service.setSuiteCases(id, body.testCaseIds(), currentUserId(jwt)));
    }

    // ------------------------------------------------------------------ steps

    public record StepBody(String action, String expectedResult) {}

    public record SetSteps(List<StepBody> steps) {}

    public record TestStepView(int position, String action, String expectedResult) {}

    private static List<TestStepView> stepViews(List<TestManagementService.Step> steps) {
        return steps.stream().map(s -> new TestStepView(s.position(), s.action(), s.expectedResult())).toList();
    }

    @GetMapping("/test-cases/{id}/steps")
    public List<TestStepView> steps(@PathVariable UUID id) {
        return stepViews(service.steps(id));
    }

    /** Replaces the test case's steps with exactly this ordered list; an empty list clears them. */
    @RequiresAccess(value = AccessRule.VERIFY, scope = RequiresAccess.Scope.ANYWHERE)
    @PutMapping("/test-cases/{id}/steps")
    public List<TestStepView> setSteps(@PathVariable UUID id, @RequestBody SetSteps body, @AuthenticationPrincipal Jwt jwt) {
        List<TestManagementService.StepInput> inputs = body.steps() == null ? List.of()
            : body.steps().stream().map(s -> new TestManagementService.StepInput(
                s == null ? null : s.action(), s == null ? null : s.expectedResult())).toList();
        return stepViews(service.setSteps(id, inputs, currentUserId(jwt)));
    }

    // ------------------------------------------------------------------- runs

    public record CreateTestRun(UUID assignedTo, String buildLabel) {}

    public record TestRunView(String id, String suiteId, String suiteName, String planId, String planName, String kind,
                               String status, String buildLabel, String assignedTo, String createdAt, String startedAt,
                               String completedAt, long caseCount) {}

    public record RunStepView(String id, int position, String action, String expectedResult, String result,
                               String actualResult, String executedBy, String executedAt) {}

    /** {@code result} is derived (PASS, FAIL, BLOCKED or NOT_RUN); the other result fields are set only for a case with no steps. */
    public record RunCaseView(String id, int position, String testCaseId, String key, String title, String description,
                               List<RunStepView> steps, String result, String actualResult, String executedBy,
                               String executedAt) {}

    public record RunSummaryView(int total, int passed, int failed, int blocked, int notRun) {}

    public record TestRunDetailView(TestRunView run, List<RunCaseView> cases, RunSummaryView summary) {}

    private static String s(Object o) {
        return o == null ? null : o.toString();
    }

    private static TestRunView view(TestManagementService.Run r) {
        return new TestRunView(s(r.id()), s(r.suiteId()), r.suiteName(), s(r.planId()), r.planName(), r.kind(), r.status(),
            r.buildLabel(), s(r.assignedTo()), s(r.createdAt()), s(r.startedAt()), s(r.completedAt()), r.caseCount());
    }

    private static TestRunDetailView view(TestManagementService.RunDetail d) {
        return new TestRunDetailView(view(d.run()), d.cases().stream().map(c -> new RunCaseView(
            s(c.id()), c.position(), s(c.testCaseId()), c.key(), c.title(), c.description(),
            c.steps().stream().map(st -> new RunStepView(s(st.id()), st.position(), st.action(), st.expectedResult(),
                st.result(), st.actualResult(), s(st.executedBy()), s(st.executedAt()))).toList(),
            c.result(), c.actualResult(), s(c.executedBy()), s(c.executedAt()))).toList(),
            new RunSummaryView(d.summary().total(), d.summary().passed(), d.summary().failed(), d.summary().blocked(),
                d.summary().notRun()));
    }

    /** Creates a PLANNED run of the suite and copies its cases and their steps into it. */
    @RequiresAccess(value = AccessRule.VERIFY, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping("/test-suites/{suiteId}/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public TestRunDetailView createRun(@PathVariable UUID suiteId, @RequestBody(required = false) CreateTestRun body,
                                        @AuthenticationPrincipal Jwt jwt) {
        CreateTestRun b = body == null ? new CreateTestRun(null, null) : body;
        return view(service.createRun(suiteId, b.assignedTo(), b.buildLabel(), currentUserId(jwt)));
    }

    @GetMapping("/test-runs")
    public List<TestRunView> listRuns(@RequestParam(required = false) UUID suiteId, @RequestParam(required = false) UUID planId,
                                       @RequestParam(required = false) String status) {
        return service.listRuns(suiteId, planId, status).stream().map(TestManagementController::view).toList();
    }

    @GetMapping("/test-runs/{id}")
    public TestRunDetailView getRun(@PathVariable UUID id) {
        return view(service.getRun(id));
    }

    // -------------------------------------------------------------- execution

    public record RecordResult(String result, String actualResult) {}

    /** PLANNED to IN_PROGRESS. */
    @RequiresAccess(value = AccessRule.VERIFY, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping("/test-runs/{id}/start")
    public TestRunDetailView startRun(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return view(execution.start(id, currentUserId(jwt)));
    }

    /** Records (or replaces, while the run is in progress) the result of one step of the run's snapshot. */
    @RequiresAccess(value = AccessRule.VERIFY, scope = RequiresAccess.Scope.ANYWHERE)
    @PutMapping("/test-runs/{id}/steps/{stepId}/result")
    public TestRunDetailView recordStepResult(@PathVariable UUID id, @PathVariable UUID stepId,
                                               @RequestBody RecordResult body, @AuthenticationPrincipal Jwt jwt) {
        return view(execution.recordStepResult(id, stepId, body.result(), body.actualResult(), currentUserId(jwt)));
    }

    /** For a case with no steps only. */
    @RequiresAccess(value = AccessRule.VERIFY, scope = RequiresAccess.Scope.ANYWHERE)
    @PutMapping("/test-runs/{id}/cases/{caseId}/result")
    public TestRunDetailView recordCaseResult(@PathVariable UUID id, @PathVariable UUID caseId,
                                               @RequestBody RecordResult body, @AuthenticationPrincipal Jwt jwt) {
        return view(execution.recordCaseResult(id, caseId, body.result(), body.actualResult(), currentUserId(jwt)));
    }

    /** IN_PROGRESS to COMPLETED; every case must have a result. */
    @RequiresAccess(value = AccessRule.VERIFY, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping("/test-runs/{id}/complete")
    public TestRunDetailView completeRun(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return view(execution.complete(id, currentUserId(jwt)));
    }
}
