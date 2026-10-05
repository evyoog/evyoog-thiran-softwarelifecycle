package com.vyoog.evidence;

import com.vyoog.platform.audit.AuditService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0924a: executing a manual run created by {@link TestManagementService#createRun}.
 * Lifecycle: PLANNED, {@link #start} to IN_PROGRESS, results recorded one step at a time,
 * {@link #complete} to COMPLETED (final: nothing can be recorded afterwards).
 *
 * <p>A result is PASS, FAIL or BLOCKED, with the actual result; the actual result is required
 * unless the step passed. Recording again while the run is IN_PROGRESS replaces the earlier result
 * (the audit event keeps the before and after). A case with no steps is judged on the case itself.
 * A run can be completed only when every case has a result.
 *
 * <p>This writes no {@code verification} row and never touches {@code requirement.status}:
 * turning results into verification records is VYB-0925 (CLAUDE.md rule 3). Evidence attachments
 * and retest are VYB-0924b.
 */
@Service
public class TestExecutionService {

    private static final Set<String> RESULTS = Set.of("PASS", "FAIL", "BLOCKED");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final TestManagementService mgmt;

    public TestExecutionService(JdbcTemplate jdbc, AuditService audit, TestManagementService mgmt) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.mgmt = mgmt;
    }

    @Transactional
    public TestManagementService.RunDetail start(UUID runId, UUID actorId) {
        String status = lock(runId);
        if (!"PLANNED".equals(status)) {
            throw new IllegalStateException("Only a planned run can be started; this one is " + status);
        }
        jdbc.update("UPDATE test_run SET status = 'IN_PROGRESS', started_at = now() WHERE id = ?", runId);
        audit.record(actorId, "test-run.started", "TEST_RUN", runId, auditMap("status", "PLANNED"), auditMap("status", "IN_PROGRESS"));
        return mgmt.getRun(runId);
    }

    @Transactional
    public TestManagementService.RunDetail recordStepResult(UUID runId, UUID runStepId, String result, String actualResult, UUID actorId) {
        requireInProgress(runId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT rs.result FROM test_run_step rs JOIN test_run_case rc ON rc.id = rs.run_case_id
             WHERE rs.id = ? AND rc.run_id = ?
            """, runStepId, runId);
        if (rows.isEmpty()) throw new NoSuchElementException("No such step in this run: " + runStepId);
        String before = (String) rows.get(0).get("result");
        String clean = checkResult(result, actualResult);
        jdbc.update("UPDATE test_run_step SET result = ?, actual_result = ?, executed_by = ?, executed_at = now() WHERE id = ?",
            result, clean, actorId, runStepId);
        audit.record(actorId, "test-run.step-recorded", "TEST_RUN", runId,
            auditMap("stepId", runStepId.toString(), "result", before), auditMap("stepId", runStepId.toString(), "result", result));
        return mgmt.getRun(runId);
    }

    /** For a case that has no steps; a case that has steps is judged through them. */
    @Transactional
    public TestManagementService.RunDetail recordCaseResult(UUID runId, UUID runCaseId, String result, String actualResult, UUID actorId) {
        requireInProgress(runId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT rc.result, (SELECT count(*) FROM test_run_step rs WHERE rs.run_case_id = rc.id) AS steps
              FROM test_run_case rc WHERE rc.id = ? AND rc.run_id = ?
            """, runCaseId, runId);
        if (rows.isEmpty()) throw new NoSuchElementException("No such case in this run: " + runCaseId);
        if (((Number) rows.get(0).get("steps")).longValue() > 0) {
            throw new IllegalStateException("This case has steps; record a result on each step instead");
        }
        String before = (String) rows.get(0).get("result");
        String clean = checkResult(result, actualResult);
        jdbc.update("UPDATE test_run_case SET result = ?, actual_result = ?, executed_by = ?, executed_at = now() WHERE id = ?",
            result, clean, actorId, runCaseId);
        audit.record(actorId, "test-run.case-recorded", "TEST_RUN", runId,
            auditMap("caseId", runCaseId.toString(), "result", before), auditMap("caseId", runCaseId.toString(), "result", result));
        return mgmt.getRun(runId);
    }

    @Transactional
    public TestManagementService.RunDetail complete(UUID runId, UUID actorId) {
        requireInProgress(runId);
        TestManagementService.RunSummary summary = mgmt.getRun(runId).summary();
        if (summary.notRun() > 0) {
            throw new IllegalStateException(summary.notRun() + " of " + summary.total()
                + " case(s) still have no result; record them before completing the run");
        }
        jdbc.update("UPDATE test_run SET status = 'COMPLETED', completed_at = now() WHERE id = ?", runId);
        audit.record(actorId, "test-run.completed", "TEST_RUN", runId, auditMap("status", "IN_PROGRESS"),
            auditMap("status", "COMPLETED", "passed", String.valueOf(summary.passed()), "failed", String.valueOf(summary.failed()),
                "blocked", String.valueOf(summary.blocked())));
        return mgmt.getRun(runId);
    }

    /** Locks the run row so a result and a completion cannot interleave; returns its status. */
    private String lock(UUID runId) {
        List<String> rows = jdbc.queryForList("SELECT status FROM test_run WHERE id = ? AND kind = 'MANUAL' FOR UPDATE", String.class, runId);
        if (rows.isEmpty()) throw new NoSuchElementException("No such test run: " + runId);
        return rows.get(0);
    }

    private void requireInProgress(UUID runId) {
        String status = lock(runId);
        if ("PLANNED".equals(status)) throw new IllegalStateException("Start the run before recording results");
        if ("COMPLETED".equals(status)) throw new IllegalStateException("This run is completed and can no longer be changed");
    }

    private static String checkResult(String result, String actualResult) {
        if (result == null || !RESULTS.contains(result)) {
            throw new IllegalArgumentException("A result is PASS, FAIL or BLOCKED");
        }
        String actual = actualResult == null || actualResult.isBlank() ? null : actualResult.strip();
        if (actual == null && !"PASS".equals(result)) {
            throw new IllegalArgumentException("Say what actually happened: an actual result is required for " + result);
        }
        return actual;
    }

    private static Map<String, Object> auditMap(String... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
