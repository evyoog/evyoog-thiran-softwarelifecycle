package com.vyoog.evidence;

import com.vyoog.attachments.AttachmentService;
import com.vyoog.detection.DetectionSweepService;
import com.vyoog.platform.audit.AuditService;
import jakarta.persistence.EntityManager;
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
 * <p>VYB-0924b: {@link #addStepEvidence}/{@link #addCaseEvidence} back a result with a file, stored as an
 * attachment of a requirement the test case verifies; {@link #retest} makes a new run of the failed and
 * blocked cases of a completed one.
 *
 * <p>VYB-0925: starting a run freezes, per case, the requirements it verifies and their current revisions
 * ({@code test_run_case_requirement}); completing it writes one {@code verification} row per case and requirement
 * from that table, bound to the frozen revision: a PASS case writes PASS, a FAIL case writes FAIL, a BLOCKED case
 * writes none (it was not tested). It never touches {@code requirement.status} (CLAUDE.md rule 3): the rows are
 * quality evidence, read by {@code requirement_verification_state}, exactly as for CI ingestion.
 */
@Service
public class TestExecutionService {

    private static final Set<String> RESULTS = Set.of("PASS", "FAIL", "BLOCKED");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final TestManagementService mgmt;
    private final AttachmentService attachments;
    private final EntityManager entityManager;
    private final DetectionSweepService detection;

    public TestExecutionService(JdbcTemplate jdbc, AuditService audit, TestManagementService mgmt,
                                 AttachmentService attachments, EntityManager entityManager,
                                 DetectionSweepService detection) {
        this.entityManager = entityManager;
        this.detection = detection;
        this.jdbc = jdbc;
        this.audit = audit;
        this.mgmt = mgmt;
        this.attachments = attachments;
    }

    @Transactional
    public TestManagementService.RunDetail start(UUID runId, UUID actorId) {
        String status = lock(runId);
        if (!"PLANNED".equals(status)) {
            throw new IllegalStateException("Only a planned run can be started; this one is " + status);
        }
        jdbc.update("UPDATE test_run SET status = 'IN_PROGRESS', started_at = now() WHERE id = ?", runId);
        // Freeze what each case verifies, and at which revision, as of now (VYB-0925).
        jdbc.update("""
            INSERT INTO test_run_case_requirement (run_case_id, requirement_id, requirement_revision)
            SELECT DISTINCT rc.id, r.id, r.revision
              FROM test_run_case rc
              JOIN trace_link tl ON tl.from_type = 'TEST' AND tl.from_id = rc.test_case_id
                                AND tl.to_type = 'REQUIREMENT' AND tl.link_type = 'VERIFIES'
              JOIN requirement r ON r.id = tl.to_id
             WHERE rc.run_id = ?
            ON CONFLICT DO NOTHING
            """, runId);
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
        TestManagementService.RunDetail before = mgmt.getRun(runId);
        TestManagementService.RunSummary summary = before.summary();
        if (summary.notRun() > 0) {
            throw new IllegalStateException(summary.notRun() + " of " + summary.total()
                + " case(s) still have no result; record them before completing the run");
        }
        jdbc.update("UPDATE test_run SET status = 'COMPLETED', completed_at = now() WHERE id = ?", runId);
        audit.record(actorId, "test-run.completed", "TEST_RUN", runId, auditMap("status", "IN_PROGRESS"),
            auditMap("status", "COMPLETED", "passed", String.valueOf(summary.passed()), "failed", String.valueOf(summary.failed()),
                "blocked", String.valueOf(summary.blocked())));
        recordVerifications(runId, before.cases(), actorId);
        return mgmt.getRun(runId);
    }

    /**
     * VYB-0925: one verification row per case and requirement frozen at start, PASS or FAIL as the case's derived
     * result says; a BLOCKED case writes nothing. The test case id is kept only while the live case still exists
     * (the run's copy is a snapshot and may outlive it).
     */
    private void recordVerifications(UUID runId, List<TestManagementService.RunCase> cases, UUID actorId) {
        int written = 0;
        java.util.Set<UUID> touched = new java.util.LinkedHashSet<>();
        for (TestManagementService.RunCase c : cases) {
            if (!"PASS".equals(c.result()) && !"FAIL".equals(c.result())) continue;
            written += jdbc.update("""
                INSERT INTO verification (requirement_id, requirement_revision, test_case_id, test_run_id, result)
                SELECT crq.requirement_id, crq.requirement_revision, (SELECT tc.id FROM test_case tc WHERE tc.id = rc.test_case_id),
                       rc.run_id, ?
                  FROM test_run_case_requirement crq JOIN test_run_case rc ON rc.id = crq.run_case_id
                 WHERE rc.id = ?
                """, c.result(), c.id());
            c.requirements().forEach(r -> touched.add(r.requirementId()));
        }
        audit.record(actorId, "test-run.verifications-recorded", "TEST_RUN", runId, null,
            auditMap("verifications", String.valueOf(written), "requirements", String.valueOf(touched.size())));
        // Same as every write path that changes what a detector reads (VYB-0161): new evidence changes noverify.
        touched.forEach(detection::rescanObject);
    }

    // -------------------------------------------------------- evidence (0924b)

    /**
     * Backs a step's result with a file while the run is IN_PROGRESS. The file is stored as an attachment of a
     * requirement the step's test case verifies (a {@code TEST --VERIFIES--> REQUIREMENT} link): {@code requirementId}
     * names which when there are several, may be omitted when there is exactly one, and is refused when there is none.
     * The attachment's filename is prefixed with the run, case and step so it cannot collide with, or be mistaken for,
     * the requirement's own files, and the link points at the exact attachment version.
     */
    @Transactional
    public TestManagementService.RunDetail addStepEvidence(UUID runId, UUID runStepId, UUID requirementId, String filename,
                                                            String contentType, byte[] bytes, UUID actorId) {
        requireInProgress(runId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT rc.test_case_id, rc.case_key, rs.position FROM test_run_step rs JOIN test_run_case rc ON rc.id = rs.run_case_id
             WHERE rs.id = ? AND rc.run_id = ?
            """, runStepId, runId);
        if (rows.isEmpty()) throw new NoSuchElementException("No such step in this run: " + runStepId);
        Map<String, Object> row = rows.get(0);
        String label = row.get("case_key") + "-step" + row.get("position");
        UUID version = store(runId, (UUID) row.get("test_case_id"), requirementId, label, filename, contentType, bytes, actorId);
        jdbc.update("INSERT INTO test_run_evidence (run_step_id, attachment_version_id, added_by) VALUES (?, ?, ?)",
            runStepId, version, actorId);
        audit.record(actorId, "test-run.evidence-added", "TEST_RUN", runId, null,
            auditMap("stepId", runStepId.toString(), "attachmentVersionId", version.toString()));
        return mgmt.getRun(runId);
    }

    /** As {@link #addStepEvidence}, for a case that has no steps. */
    @Transactional
    public TestManagementService.RunDetail addCaseEvidence(UUID runId, UUID runCaseId, UUID requirementId, String filename,
                                                            String contentType, byte[] bytes, UUID actorId) {
        requireInProgress(runId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT rc.test_case_id, rc.case_key, (SELECT count(*) FROM test_run_step rs WHERE rs.run_case_id = rc.id) AS steps
              FROM test_run_case rc WHERE rc.id = ? AND rc.run_id = ?
            """, runCaseId, runId);
        if (rows.isEmpty()) throw new NoSuchElementException("No such case in this run: " + runCaseId);
        Map<String, Object> row = rows.get(0);
        if (((Number) row.get("steps")).longValue() > 0) {
            throw new IllegalStateException("This case has steps; attach the evidence to the step it backs");
        }
        UUID version = store(runId, (UUID) row.get("test_case_id"), requirementId, (String) row.get("case_key"), filename,
            contentType, bytes, actorId);
        jdbc.update("INSERT INTO test_run_evidence (run_case_id, attachment_version_id, added_by) VALUES (?, ?, ?)",
            runCaseId, version, actorId);
        audit.record(actorId, "test-run.evidence-added", "TEST_RUN", runId, null,
            auditMap("caseId", runCaseId.toString(), "attachmentVersionId", version.toString()));
        return mgmt.getRun(runId);
    }

    private UUID store(UUID runId, UUID testCaseId, UUID requirementId, String label, String filename, String contentType,
                       byte[] bytes, UUID actorId) {
        if (filename == null || filename.isBlank()) throw new IllegalArgumentException("The uploaded file has no name");
        List<UUID> verified = testCaseId == null ? List.of() : jdbc.queryForList("""
            SELECT to_id FROM trace_link WHERE from_type = 'TEST' AND from_id = ? AND to_type = 'REQUIREMENT' AND link_type = 'VERIFIES'
            """, UUID.class, testCaseId);
        UUID target;
        if (requirementId != null) {
            if (!verified.contains(requirementId)) {
                throw new IllegalStateException("This test case does not verify that requirement, so evidence cannot be attached to it");
            }
            target = requirementId;
        } else if (verified.isEmpty()) {
            throw new IllegalStateException("This test case verifies no requirement, so there is nowhere to attach evidence");
        } else if (verified.size() > 1) {
            throw new IllegalArgumentException("This test case verifies " + verified.size() + " requirements; say which one the evidence belongs to");
        } else {
            target = verified.get(0);
        }
        String name = "run-" + runId.toString().substring(0, 8) + "-" + label + "-" + filename;
        UUID version = attachments.upload(target, name, contentType, bytes, actorId).version().getId();
        // The attachment version is a JPA entity whose INSERT is still unflushed; the evidence row that follows is raw
        // SQL with a foreign key to it (the same JPA-then-JDBC ordering TestCaseService.draft flushes for).
        entityManager.flush();
        return version;
    }

    // ------------------------------------------------------------ retest (0924b)

    /**
     * A new PLANNED run of the failed and blocked cases of a completed run, copied from that run's snapshot (not the
     * live cases) with every result blank, linked back through {@code retest_of}. The original run is never changed.
     * Refused while an earlier retest of the same run is still open, so one failure is not retested twice at once.
     */
    @Transactional
    public TestManagementService.RunDetail retest(UUID runId, UUID assignedTo, String buildLabel, UUID actorId) {
        String status = lock(runId);
        if (!"COMPLETED".equals(status)) throw new IllegalStateException("Only a completed run can be retested; this one is " + status);
        TestManagementService.RunDetail source = mgmt.getRun(runId);
        List<TestManagementService.RunCase> failed = source.cases().stream()
            .filter(c -> "FAIL".equals(c.result()) || "BLOCKED".equals(c.result())).toList();
        if (failed.isEmpty()) throw new IllegalStateException("Nothing failed or was blocked in this run, so there is nothing to retest");
        Long open = jdbc.queryForObject("SELECT count(*) FROM test_run WHERE retest_of = ? AND status <> 'COMPLETED'", Long.class, runId);
        if (open != null && open > 0) throw new IllegalStateException("A retest of this run is already open");
        if (assignedTo != null) {
            Long n = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id = ?", Long.class, assignedTo);
            if (n == null || n == 0) throw new NoSuchElementException("No such user: " + assignedTo);
        }
        String label = buildLabel == null || buildLabel.isBlank() ? source.run().buildLabel() : buildLabel.strip();
        UUID retestId = jdbc.queryForObject("""
            INSERT INTO test_run (kind, status, suite_id, build_label, assigned_to, created_by, retest_of)
            VALUES ('MANUAL', 'PLANNED', ?, ?, ?, ?, ?) RETURNING id
            """, UUID.class, source.run().suiteId(), label, assignedTo, actorId, runId);
        int position = 1;
        for (TestManagementService.RunCase c : failed) {
            UUID newCase = jdbc.queryForObject("""
                INSERT INTO test_run_case (run_id, position, test_case_id, case_key, title, description)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, UUID.class, retestId, position++, c.testCaseId(), c.key(), c.title(), c.description());
            for (TestManagementService.RunStep st : c.steps()) {
                jdbc.update("INSERT INTO test_run_step (run_case_id, position, action, expected_result) VALUES (?, ?, ?, ?)",
                    newCase, st.position(), st.action(), st.expectedResult());
            }
        }
        audit.record(actorId, "test-run.retest-created", "TEST_RUN", retestId, null,
            auditMap("retestOf", runId.toString(), "caseCount", String.valueOf(failed.size())));
        return mgmt.getRun(retestId);
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
