package com.vyoog.evidence;

import com.vyoog.defect.Defect;
import com.vyoog.defect.DefectService;
import com.vyoog.defect.DefectSeverity;
import com.vyoog.defect.FoundIn;
import com.vyoog.platform.audit.AuditService;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0926: raising a defect from a failed step of a manual run, prefilled with the test and the run.
 *
 * <p>Only a step whose result is FAIL can raise one (a case with no steps: only a case whose own result is FAIL),
 * and only once the run has been started. The defect is raised through {@link DefectService#raise}, so routing,
 * notification and audit are exactly those of any other defect; this adds a link from the defect to the failed
 * step (or run case), through which the test, run, expected and actual result are read. One defect per failed
 * step (a database rule): a second attempt is refused and names the existing one. A step of a retest is a
 * different step and can have its own.
 *
 * <p>The requirement is one the case verified when the run was started ({@code test_run_case_requirement}): the
 * only candidate is chosen for the caller, several must be chosen between, none leaves the defect untraced, which
 * defects allow (VYB-0320).
 */
@Service
public class TestDefectService {

    /** The prefilled values and the read-only context behind them; the caller may change any suggested value. */
    public record DefectDraft(String title, String severity, String foundIn, UUID requirementId,
                               List<TestManagementService.TestedRequirement> candidates, UUID runId, String buildLabel,
                               String planName, String suiteName, String testKey, String testTitle, Integer stepPosition,
                               String action, String expectedResult, String actualResult,
                               TestManagementService.DefectRef existingDefect) {}

    public record Raised(Defect defect, UUID runId, UUID runStepId, UUID runCaseId) {}

    private record Target(UUID runId, String runStatus, String buildLabel, String planName, String suiteName, UUID stepId,
                           UUID caseId, String caseKey, String caseTitle, Integer position, String action,
                           String expected, String result, String actual) {}

    private final JdbcTemplate jdbc;
    private final DefectService defects;
    private final AuditService audit;
    private final EntityManager entityManager;

    public TestDefectService(JdbcTemplate jdbc, DefectService defects, AuditService audit, EntityManager entityManager) {
        this.jdbc = jdbc;
        this.defects = defects;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    public DefectDraft draftFromStep(UUID runId, UUID runStepId) {
        return draft(stepTarget(runId, runStepId));
    }

    public DefectDraft draftFromCase(UUID runId, UUID runCaseId) {
        return draft(caseTarget(runId, runCaseId));
    }

    @Transactional
    public Raised raiseFromStep(UUID runId, UUID runStepId, String title, String severity, String foundIn,
                                UUID requirementId, UUID actorId) {
        return raise(stepTarget(runId, runStepId), title, severity, foundIn, requirementId, actorId);
    }

    @Transactional
    public Raised raiseFromCase(UUID runId, UUID runCaseId, String title, String severity, String foundIn,
                                UUID requirementId, UUID actorId) {
        return raise(caseTarget(runId, runCaseId), title, severity, foundIn, requirementId, actorId);
    }

    // ------------------------------------------------------------------ core

    private DefectDraft draft(Target t) {
        List<TestManagementService.TestedRequirement> candidates = candidates(t.caseId());
        TestManagementService.DefectRef existing = existing(t);
        return new DefectDraft(defaultTitle(t), DefectSeverity.MEDIUM.name(), FoundIn.QA.name(),
            candidates.size() == 1 ? candidates.get(0).requirementId() : null, candidates, t.runId(), t.buildLabel(),
            t.planName(), t.suiteName(), t.caseKey(), t.caseTitle(), t.position(), t.action(), t.expected(), t.actual(), existing);
    }

    private Raised raise(Target t, String title, String severity, String foundIn, UUID requirementId, UUID actorId) {
        if (!"FAIL".equals(t.result())) {
            throw new IllegalStateException("Only a failed " + (t.stepId() != null ? "step" : "case") + " can raise a defect");
        }
        TestManagementService.DefectRef existing = existing(t);
        if (existing != null) {
            throw new IllegalStateException("A defect has already been raised from this failure: " + existing.key());
        }
        List<TestManagementService.TestedRequirement> candidates = candidates(t.caseId());
        UUID requirement;
        if (requirementId != null) {
            if (candidates.stream().noneMatch(c -> c.requirementId().equals(requirementId))) {
                throw new IllegalStateException("This test case did not verify that requirement when the run was started");
            }
            requirement = requirementId;
        } else if (candidates.size() > 1) {
            throw new IllegalArgumentException("This test case verifies " + candidates.size()
                + " requirements; say which one the defect is against");
        } else {
            requirement = candidates.isEmpty() ? null : candidates.get(0).requirementId();
        }
        String cleanTitle = title == null || title.isBlank() ? defaultTitle(t) : title.strip();
        Defect d = defects.raise(cleanTitle,
            severity == null || severity.isBlank() ? DefectSeverity.MEDIUM : DefectSeverity.valueOf(severity),
            requirement, foundIn == null || foundIn.isBlank() ? FoundIn.QA : FoundIn.valueOf(foundIn), actorId);
        // DefectService saves a JPA entity whose INSERT is still unflushed; the link is raw SQL on that row.
        entityManager.flush();
        jdbc.update("UPDATE defect SET raised_from_run_step_id = ?, raised_from_run_case_id = ? WHERE id = ?",
            t.stepId(), t.stepId() == null ? t.caseId() : null, d.getId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("defectKey", d.getKey());
        after.put(t.stepId() != null ? "stepId" : "caseId", (t.stepId() != null ? t.stepId() : t.caseId()).toString());
        audit.record(actorId, "test-run.defect-raised", "TEST_RUN", t.runId(), null, after);
        return new Raised(d, t.runId(), t.stepId(), t.stepId() == null ? t.caseId() : null);
    }

    private static String defaultTitle(Target t) {
        String text = t.stepId() != null
            ? t.caseKey() + " step " + t.position() + " failed: " + t.action()
            : t.caseKey() + " failed: " + t.caseTitle();
        return text.length() > 200 ? text.substring(0, 197) + "..." : text;
    }

    private TestManagementService.DefectRef existing(Target t) {
        List<TestManagementService.DefectRef> rows = jdbc.query(
            "SELECT id, key FROM defect WHERE " + (t.stepId() != null ? "raised_from_run_step_id" : "raised_from_run_case_id") + " = ?",
            (rs, i) -> new TestManagementService.DefectRef(rs.getObject("id", UUID.class), rs.getString("key")),
            t.stepId() != null ? t.stepId() : t.caseId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<TestManagementService.TestedRequirement> candidates(UUID runCaseId) {
        return jdbc.query("""
            SELECT crq.requirement_id, r.key, crq.requirement_revision, r.revision AS current_revision
              FROM test_run_case_requirement crq JOIN requirement r ON r.id = crq.requirement_id
             WHERE crq.run_case_id = ? ORDER BY r.key
            """, (rs, i) -> new TestManagementService.TestedRequirement(rs.getObject("requirement_id", UUID.class),
                rs.getString("key"), rs.getInt("requirement_revision"), rs.getInt("current_revision")), runCaseId);
    }

    // --------------------------------------------------------------- targets

    private static final String RUN_JOIN = """
          FROM test_run r
          LEFT JOIN test_suite su ON su.id = r.suite_id
          LEFT JOIN test_plan p ON p.id = su.plan_id
        """;

    private Target stepTarget(UUID runId, UUID runStepId) {
        List<Target> rows = jdbc.query("""
            SELECT r.id AS run_id, r.status, r.build_label, p.name AS plan_name, su.name AS suite_name,
                   rs.id AS step_id, rc.id AS case_id, rc.case_key, rc.title, rs.position, rs.action, rs.expected_result,
                   rs.result, rs.actual_result
            """ + RUN_JOIN + """
          JOIN test_run_case rc ON rc.run_id = r.id
          JOIN test_run_step rs ON rs.run_case_id = rc.id
         WHERE r.id = ? AND r.kind = 'MANUAL' AND rs.id = ?
            """, (rs, i) -> target(rs), runId, runStepId);
        if (rows.isEmpty()) throw new NoSuchElementException("No such step in this run: " + runStepId);
        return started(rows.get(0));
    }

    private Target caseTarget(UUID runId, UUID runCaseId) {
        List<Target> rows = jdbc.query("""
            SELECT r.id AS run_id, r.status, r.build_label, p.name AS plan_name, su.name AS suite_name,
                   NULL::uuid AS step_id, rc.id AS case_id, rc.case_key, rc.title, NULL::int AS position, NULL AS action,
                   NULL AS expected_result, rc.result, rc.actual_result,
                   (SELECT count(*) FROM test_run_step x WHERE x.run_case_id = rc.id) AS steps
            """ + RUN_JOIN + """
          JOIN test_run_case rc ON rc.run_id = r.id
         WHERE r.id = ? AND r.kind = 'MANUAL' AND rc.id = ?
            """, (rs, i) -> {
                if (rs.getLong("steps") > 0) {
                    throw new IllegalStateException("This case has steps; raise the defect from the failed step");
                }
                return target(rs);
            }, runId, runCaseId);
        if (rows.isEmpty()) throw new NoSuchElementException("No such case in this run: " + runCaseId);
        return started(rows.get(0));
    }

    private static Target started(Target t) {
        if ("PLANNED".equals(t.runStatus())) throw new IllegalStateException("Start the run before raising a defect from it");
        return t;
    }

    private static Target target(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Target(rs.getObject("run_id", UUID.class), rs.getString("status"), rs.getString("build_label"),
            rs.getString("plan_name"), rs.getString("suite_name"), rs.getObject("step_id", UUID.class),
            rs.getObject("case_id", UUID.class), rs.getString("case_key"), rs.getString("title"),
            (Integer) rs.getObject("position"), rs.getString("action"), rs.getString("expected_result"),
            rs.getString("result"), rs.getString("actual_result"));
    }
}
