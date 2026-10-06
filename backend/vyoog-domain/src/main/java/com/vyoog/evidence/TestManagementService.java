package com.vyoog.evidence;

import com.vyoog.platform.audit.AuditService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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
 * VYB-0923: manual test management — plans, suites, structured steps and run creation.
 *
 * <p>A <b>plan</b> belongs to an application (optionally a release) and holds named, ordered
 * <b>suites</b>; a suite is an ordered group of existing test cases. A test case carries
 * ordered <b>steps</b> (action + expected result) beside its free-text description. A <b>run</b>
 * is the execution of one suite: creating it copies the suite's cases and their steps into
 * {@code test_run_case}/{@code test_run_step}, so editing a case afterwards never rewrites
 * what was run. Recording results against those steps is VYB-0924; turning them into
 * verification records is VYB-0925 — this service writes no result and never touches
 * {@code requirement.status} (CLAUDE.md rule 3).
 *
 * <p>Raw SQL by this codebase's style (joins, ordered replacement, {@code INSERT ... SELECT}
 * for the snapshot). Every state change records an audit event.
 */
@Service
public class TestManagementService {

    public record Plan(UUID id, String key, String name, String description, UUID applicationId, String applicationName,
                        UUID releaseId, UUID createdBy, Instant createdAt, long suiteCount, long runCount) {}

    public record Suite(UUID id, UUID planId, String name, String description, int position, long caseCount) {}

    public record SuiteCase(UUID testCaseId, String key, String title, int position) {}

    public record Step(int position, String action, String expectedResult) {}

    public record StepInput(String action, String expectedResult) {}

    public record Run(UUID id, UUID suiteId, String suiteName, UUID planId, String planName, String kind, String status,
                       String buildLabel, UUID assignedTo, String assignedToName, UUID createdBy, Instant createdAt, Instant startedAt,
                       Instant completedAt, long caseCount, UUID retestOf) {}

    /** {@code result} is null until recorded (VYB-0924a); then PASS, FAIL or BLOCKED. */
    /**
     * One file backing a result (VYB-0924b): an exact version of an attachment of {@code requirementId}, which is
     * downloaded through that requirement's attachment endpoints.
     */
    public record Evidence(UUID id, UUID attachmentId, int version, UUID requirementId, String requirementKey,
                            String filename, String contentType, Long sizeBytes, UUID addedBy, Instant addedAt) {}

    /** The defect raised from a failed step or case (VYB-0926), if any. */
    public record DefectRef(UUID id, String key) {}

    public record RunStep(UUID id, int position, String action, String expectedResult, String result,
                           String actualResult, UUID executedBy, String executedByName, Instant executedAt,
                           List<Evidence> evidence, DefectRef defect) {}

    /**
     * {@code result} is derived, never stored: FAIL if any step failed, else BLOCKED if any was blocked, else
     * NOT_RUN if any is unrecorded, else PASS. A case with no steps is judged on itself: {@code recordedResult},
     * {@code actualResult}, {@code executedBy} and {@code executedAt} hold that judgement and are null for a case that has steps.
     */
    public record RunCase(UUID id, int position, UUID testCaseId, String key, String title, String description,
                           List<RunStep> steps, String result, String actualResult, UUID executedBy, String executedByName, Instant executedAt,
                           List<Evidence> evidence, List<TestedRequirement> requirements, DefectRef defect) {}

    /**
     * A requirement the case verifies, frozen when the run was started (VYB-0925): {@code testedRevision} is what the
     * verification record binds to; {@code currentRevision} is the requirement's revision now, so a larger one means
     * the requirement was edited after (or during) the run and the result is stale.
     */
    public record TestedRequirement(UUID requirementId, String key, int testedRevision, int currentRevision) {}

    /** {@code verificationsRecorded}: the verification rows written when the run was completed (0 until then). */
    public record RunSummary(int total, int passed, int failed, int blocked, int notRun, int verificationsRecorded) {}

    public record RunDetail(Run run, List<RunCase> cases, RunSummary summary) {}

    static String caseResult(List<RunStep> steps, String ownResult) {
        if (steps.isEmpty()) return ownResult == null ? "NOT_RUN" : ownResult;
        boolean blocked = false, unrecorded = false;
        for (RunStep st : steps) {
            if ("FAIL".equals(st.result())) return "FAIL";
            if ("BLOCKED".equals(st.result())) blocked = true;
            if (st.result() == null) unrecorded = true;
        }
        return blocked ? "BLOCKED" : unrecorded ? "NOT_RUN" : "PASS";
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public TestManagementService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ plans

    private static final String PLAN_SELECT = """
        SELECT p.id, p.key, p.name, p.description, p.application_id, a.name AS application_name, p.release_id, p.created_by, p.created_at,
               (SELECT count(*) FROM test_suite s WHERE s.plan_id = p.id) AS suites,
               (SELECT count(*) FROM test_run r JOIN test_suite s ON s.id = r.suite_id WHERE s.plan_id = p.id) AS runs
          FROM test_plan p JOIN application a ON a.id = p.application_id
        """;

    private Plan plan(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Plan(rs.getObject("id", UUID.class), rs.getString("key"), rs.getString("name"),
            rs.getString("description"), rs.getObject("application_id", UUID.class), rs.getString("application_name"),
            rs.getObject("release_id", UUID.class), rs.getObject("created_by", UUID.class),
            rs.getTimestamp("created_at").toInstant(), rs.getLong("suites"), rs.getLong("runs"));
    }

    @Transactional
    public Plan createPlan(String name, String description, UUID applicationId, UUID releaseId, UUID actorId) {
        String cleanName = requireText(name, "A test plan needs a name");
        requireExists("application", applicationId, "No such application: ");
        if (releaseId != null) requireExists("release", releaseId, "No such release: ");
        Long n = jdbc.queryForObject("SELECT nextval('test_plan_key_seq')", Long.class);
        String key = "TP-" + n;
        UUID id = jdbc.queryForObject("""
            INSERT INTO test_plan (key, name, description, application_id, release_id, created_by)
            VALUES (?, ?, ?, ?, ?, ?) RETURNING id
            """, UUID.class, key, cleanName, blankToNull(description), applicationId, releaseId, actorId);
        audit.record(actorId, "test-plan.created", "TEST_PLAN", id, null,
            auditMap("key", key, "name", cleanName, "applicationId", applicationId.toString()));
        return getPlan(id);
    }

    @Transactional
    public Plan updatePlan(UUID id, String name, String description, UUID releaseId, UUID actorId) {
        Plan before = getPlan(id);
        String cleanName = requireText(name, "A test plan needs a name");
        if (releaseId != null) requireExists("release", releaseId, "No such release: ");
        jdbc.update("UPDATE test_plan SET name = ?, description = ?, release_id = ? WHERE id = ?",
            cleanName, blankToNull(description), releaseId, id);
        audit.record(actorId, "test-plan.updated", "TEST_PLAN", id,
            auditMap("name", before.name(), "releaseId", str(before.releaseId())),
            auditMap("name", cleanName, "releaseId", str(releaseId)));
        return getPlan(id);
    }

    /** A plan that has been run is history: refused, not silently cascaded over its runs. */
    @Transactional
    public void deletePlan(UUID id, UUID actorId) {
        Plan plan = getPlan(id);
        if (plan.runCount() > 0) {
            throw new IllegalStateException(
                "Test plan " + plan.key() + " has been run " + plan.runCount() + " time(s) and cannot be deleted");
        }
        jdbc.update("DELETE FROM test_plan WHERE id = ?", id);
        audit.record(actorId, "test-plan.deleted", "TEST_PLAN", id, auditMap("key", plan.key(), "name", plan.name()), null);
    }

    public Plan getPlan(UUID id) {
        List<Plan> rows = jdbc.query(PLAN_SELECT + " WHERE p.id = ?", (rs, i) -> plan(rs), id);
        if (rows.isEmpty()) throw new NoSuchElementException("No such test plan: " + id);
        return rows.get(0);
    }

    public List<Plan> listPlans(UUID applicationId, UUID releaseId) {
        StringBuilder sql = new StringBuilder(PLAN_SELECT).append(" WHERE true");
        List<Object> args = new ArrayList<>();
        if (applicationId != null) { sql.append(" AND p.application_id = ?"); args.add(applicationId); }
        if (releaseId != null) { sql.append(" AND p.release_id = ?"); args.add(releaseId); }
        sql.append(" ORDER BY p.created_at DESC, p.key");
        return jdbc.query(sql.toString(), (rs, i) -> plan(rs), args.toArray());
    }

    // ----------------------------------------------------------------- suites

    private static final String SUITE_SELECT = """
        SELECT s.id, s.plan_id, s.name, s.description, s.position,
               (SELECT count(*) FROM test_suite_case c WHERE c.suite_id = s.id) AS cases
          FROM test_suite s
        """;

    private Suite suite(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Suite(rs.getObject("id", UUID.class), rs.getObject("plan_id", UUID.class), rs.getString("name"),
            rs.getString("description"), rs.getInt("position"), rs.getLong("cases"));
    }

    @Transactional
    public Suite createSuite(UUID planId, String name, String description, UUID actorId) {
        Plan plan = getPlan(planId);
        String cleanName = requireText(name, "A test suite needs a name");
        if (suiteNameTaken(planId, cleanName, null)) {
            throw new IllegalStateException("Test plan " + plan.key() + " already has a suite named '" + cleanName + "'");
        }
        UUID id = jdbc.queryForObject("""
            INSERT INTO test_suite (plan_id, name, description, position)
            VALUES (?, ?, ?, (SELECT coalesce(max(position), 0) + 1 FROM test_suite WHERE plan_id = ?)) RETURNING id
            """, UUID.class, planId, cleanName, blankToNull(description), planId);
        audit.record(actorId, "test-suite.created", "TEST_SUITE", id, null,
            auditMap("planId", planId.toString(), "name", cleanName));
        return getSuite(id);
    }

    @Transactional
    public Suite updateSuite(UUID id, String name, String description, UUID actorId) {
        Suite before = getSuite(id);
        String cleanName = requireText(name, "A test suite needs a name");
        if (suiteNameTaken(before.planId(), cleanName, id)) {
            throw new IllegalStateException("This test plan already has a suite named '" + cleanName + "'");
        }
        jdbc.update("UPDATE test_suite SET name = ?, description = ? WHERE id = ?", cleanName, blankToNull(description), id);
        audit.record(actorId, "test-suite.updated", "TEST_SUITE", id, auditMap("name", before.name()), auditMap("name", cleanName));
        return getSuite(id);
    }

    @Transactional
    public void deleteSuite(UUID id, UUID actorId) {
        Suite suite = getSuite(id);
        Long runs = jdbc.queryForObject("SELECT count(*) FROM test_run WHERE suite_id = ?", Long.class, id);
        if (runs != null && runs > 0) {
            throw new IllegalStateException("Test suite '" + suite.name() + "' has been run " + runs + " time(s) and cannot be deleted");
        }
        jdbc.update("DELETE FROM test_suite WHERE id = ?", id);
        audit.record(actorId, "test-suite.deleted", "TEST_SUITE", id,
            auditMap("planId", suite.planId().toString(), "name", suite.name()), null);
    }

    public Suite getSuite(UUID id) {
        List<Suite> rows = jdbc.query(SUITE_SELECT + " WHERE s.id = ?", (rs, i) -> suite(rs), id);
        if (rows.isEmpty()) throw new NoSuchElementException("No such test suite: " + id);
        return rows.get(0);
    }

    public List<Suite> listSuites(UUID planId) {
        getPlan(planId);
        return jdbc.query(SUITE_SELECT + " WHERE s.plan_id = ? ORDER BY s.position", (rs, i) -> suite(rs), planId);
    }

    public List<SuiteCase> suiteCases(UUID suiteId) {
        getSuite(suiteId);
        return jdbc.query("""
            SELECT c.test_case_id, t.key, t.title, c.position
              FROM test_suite_case c JOIN test_case t ON t.id = c.test_case_id
             WHERE c.suite_id = ? ORDER BY c.position
            """, (rs, i) -> new SuiteCase(rs.getObject("test_case_id", UUID.class), rs.getString("key"),
                rs.getString("title"), rs.getInt("position")), suiteId);
    }

    /**
     * Replaces the suite's membership with exactly this ordered list of existing test cases —
     * one atomic statement of intent rather than add/remove/reorder calls. Position is the
     * list order, 1-based. A case may appear once.
     */
    @Transactional
    public List<SuiteCase> setSuiteCases(UUID suiteId, List<UUID> orderedTestCaseIds, UUID actorId) {
        getSuite(suiteId);
        List<UUID> ids = orderedTestCaseIds == null ? List.of() : orderedTestCaseIds;
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("A test case can appear in a suite only once");
        }
        for (UUID testCaseId : ids) requireExists("test_case", testCaseId, "No such test case: ");
        jdbc.update("DELETE FROM test_suite_case WHERE suite_id = ?", suiteId);
        int position = 1;
        for (UUID testCaseId : ids) {
            jdbc.update("INSERT INTO test_suite_case (suite_id, test_case_id, position) VALUES (?, ?, ?)",
                suiteId, testCaseId, position++);
        }
        audit.record(actorId, "test-suite.cases-set", "TEST_SUITE", suiteId, null,
            auditMap("caseCount", String.valueOf(ids.size())));
        return suiteCases(suiteId);
    }

    private boolean suiteNameTaken(UUID planId, String name, UUID exceptSuiteId) {
        Long n = jdbc.queryForObject("""
            SELECT count(*) FROM test_suite WHERE plan_id = ? AND name = ? AND (?::uuid IS NULL OR id <> ?::uuid)
            """, Long.class, planId, name, exceptSuiteId, exceptSuiteId);
        return n != null && n > 0;
    }

    // ------------------------------------------------------------------ steps

    public List<Step> steps(UUID testCaseId) {
        requireExists("test_case", testCaseId, "No such test case: ");
        return jdbc.query("SELECT position, action, expected_result FROM test_step WHERE test_case_id = ? ORDER BY position",
            (rs, i) -> new Step(rs.getInt("position"), rs.getString("action"), rs.getString("expected_result")), testCaseId);
    }

    /**
     * Replaces a test case's steps with exactly this ordered list. Every step needs both an
     * action and an expected result: a step with nothing to compare against cannot be judged
     * pass or fail when it is executed (VYB-0924). An empty list clears the steps; the
     * free-text description is never touched. Runs created earlier keep their own copy.
     */
    @Transactional
    public List<Step> setSteps(UUID testCaseId, List<StepInput> inputs, UUID actorId) {
        requireExists("test_case", testCaseId, "No such test case: ");
        List<StepInput> list = inputs == null ? List.of() : inputs;
        int n = 1;
        for (StepInput s : list) {
            requireText(s == null ? null : s.action(), "Step " + n + " needs an action");
            requireText(s.expectedResult(), "Step " + n + " needs an expected result");
            n++;
        }
        jdbc.update("DELETE FROM test_step WHERE test_case_id = ?", testCaseId);
        int position = 1;
        for (StepInput s : list) {
            jdbc.update("INSERT INTO test_step (test_case_id, position, action, expected_result) VALUES (?, ?, ?, ?)",
                testCaseId, position++, s.action().strip(), s.expectedResult().strip());
        }
        audit.record(actorId, "test-step.set", "TEST_CASE", testCaseId, null,
            auditMap("stepCount", String.valueOf(list.size())));
        return steps(testCaseId);
    }

    // ------------------------------------------------------------------- runs

    private static final String RUN_SELECT = """
        SELECT r.id, r.suite_id, s.name AS suite_name, s.plan_id, p.name AS plan_name, r.kind, r.status, r.build_label,
               r.assigned_to, au.display_name AS assigned_to_name, r.created_by, r.created_at, r.started_at, r.completed_at, r.retest_of,
               (SELECT count(*) FROM test_run_case c WHERE c.run_id = r.id) AS cases
          FROM test_run r
          LEFT JOIN test_suite s ON s.id = r.suite_id
          LEFT JOIN test_plan p ON p.id = s.plan_id
          LEFT JOIN app_user au ON au.id = r.assigned_to
        """;

    private Run run(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Run(rs.getObject("id", UUID.class), rs.getObject("suite_id", UUID.class), rs.getString("suite_name"),
            rs.getObject("plan_id", UUID.class), rs.getString("plan_name"), rs.getString("kind"), rs.getString("status"),
            rs.getString("build_label"), rs.getObject("assigned_to", UUID.class), rs.getString("assigned_to_name"),
            rs.getObject("created_by", UUID.class),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
            rs.getLong("cases"), rs.getObject("retest_of", UUID.class));
    }

    /**
     * Creates a PLANNED manual run of one suite and copies the suite's cases (key, title,
     * description) and each case's steps into the run — a snapshot, so what is executed is
     * exactly what the plan said when the run was created, however the cases change later.
     * A suite with no cases cannot be run.
     */
    @Transactional
    public RunDetail createRun(UUID suiteId, UUID assignedTo, String buildLabel, UUID actorId) {
        Suite suite = getSuite(suiteId);
        if (suite.caseCount() == 0) {
            throw new IllegalStateException("Test suite '" + suite.name() + "' has no test cases to run");
        }
        if (assignedTo != null) requireExists("app_user", assignedTo, "No such user: ");
        UUID runId = jdbc.queryForObject("""
            INSERT INTO test_run (kind, status, suite_id, build_label, assigned_to, created_by)
            VALUES ('MANUAL', 'PLANNED', ?, ?, ?, ?) RETURNING id
            """, UUID.class, suiteId, blankToNull(buildLabel), assignedTo, actorId);
        jdbc.update("""
            INSERT INTO test_run_case (run_id, position, test_case_id, case_key, title, description)
            SELECT ?, c.position, t.id, t.key, t.title, t.description
              FROM test_suite_case c JOIN test_case t ON t.id = c.test_case_id
             WHERE c.suite_id = ?
            """, runId, suiteId);
        jdbc.update("""
            INSERT INTO test_run_step (run_case_id, position, action, expected_result)
            SELECT rc.id, st.position, st.action, st.expected_result
              FROM test_run_case rc JOIN test_step st ON st.test_case_id = rc.test_case_id
             WHERE rc.run_id = ?
            """, runId);
        audit.record(actorId, "test-run.created", "TEST_RUN", runId, null,
            auditMap("suiteId", suiteId.toString(), "caseCount", String.valueOf(suite.caseCount()),
                "assignedTo", str(assignedTo)));
        return getRun(runId);
    }

    public RunDetail getRun(UUID id) {
        List<Run> rows = jdbc.query(RUN_SELECT + " WHERE r.id = ?", (rs, i) -> run(rs), id);
        if (rows.isEmpty()) throw new NoSuchElementException("No such test run: " + id);
        Map<UUID, List<Evidence>> evidenceByOwner = new LinkedHashMap<>(); // keyed by run step id or run case id
        jdbc.query("""
            SELECT e.id, coalesce(e.run_step_id, e.run_case_id) AS owner_id, a.id AS attachment_id, v.version, a.requirement_id,
                   r.key AS requirement_key, a.filename, v.content_type, v.size_bytes, e.added_by, e.added_at
              FROM test_run_evidence e
              JOIN attachment_version v ON v.id = e.attachment_version_id
              JOIN attachment a ON a.id = v.attachment_id
              JOIN requirement r ON r.id = a.requirement_id
              LEFT JOIN test_run_step rs ON rs.id = e.run_step_id
              JOIN test_run_case rc ON rc.id = coalesce(rs.run_case_id, e.run_case_id)
             WHERE rc.run_id = ? ORDER BY e.added_at, e.id
            """, rs -> {
                evidenceByOwner.computeIfAbsent(rs.getObject("owner_id", UUID.class), k -> new ArrayList<>())
                    .add(new Evidence(rs.getObject("id", UUID.class), rs.getObject("attachment_id", UUID.class),
                        rs.getInt("version"), rs.getObject("requirement_id", UUID.class), rs.getString("requirement_key"),
                        rs.getString("filename"), rs.getString("content_type"), (Long) rs.getObject("size_bytes"),
                        rs.getObject("added_by", UUID.class), instant(rs.getTimestamp("added_at"))));
            }, id);
        Map<UUID, List<TestedRequirement>> requirementsByCase = new LinkedHashMap<>();
        jdbc.query("""
            SELECT crq.run_case_id, crq.requirement_id, r.key, crq.requirement_revision, r.revision AS current_revision
              FROM test_run_case_requirement crq
              JOIN test_run_case rc ON rc.id = crq.run_case_id
              JOIN requirement r ON r.id = crq.requirement_id
             WHERE rc.run_id = ? ORDER BY r.key
            """, rs -> {
                requirementsByCase.computeIfAbsent(rs.getObject("run_case_id", UUID.class), k -> new ArrayList<>())
                    .add(new TestedRequirement(rs.getObject("requirement_id", UUID.class), rs.getString("key"),
                        rs.getInt("requirement_revision"), rs.getInt("current_revision")));
            }, id);
        Map<UUID, DefectRef> defectByOwner = new LinkedHashMap<>(); // keyed by run step id or run case id
        jdbc.query("""
            SELECT d.id, d.key, coalesce(d.raised_from_run_step_id, d.raised_from_run_case_id) AS owner_id
              FROM defect d
              LEFT JOIN test_run_step rs ON rs.id = d.raised_from_run_step_id
              JOIN test_run_case rc ON rc.id = coalesce(rs.run_case_id, d.raised_from_run_case_id)
             WHERE rc.run_id = ?
            """, rs -> {
                defectByOwner.put(rs.getObject("owner_id", UUID.class),
                    new DefectRef(rs.getObject("id", UUID.class), rs.getString("key")));
            }, id);
        Map<UUID, List<RunStep>> stepsByCase = new LinkedHashMap<>();
        jdbc.query("""
            SELECT rs.id, rs.run_case_id, rs.position, rs.action, rs.expected_result, rs.result, rs.actual_result,
                   rs.executed_by, u.display_name AS executed_by_name, rs.executed_at
              FROM test_run_step rs JOIN test_run_case rc ON rc.id = rs.run_case_id
              LEFT JOIN app_user u ON u.id = rs.executed_by
             WHERE rc.run_id = ? ORDER BY rc.position, rs.position
            """, rs -> {
                stepsByCase.computeIfAbsent(rs.getObject("run_case_id", UUID.class), k -> new ArrayList<>())
                    .add(new RunStep(rs.getObject("id", UUID.class), rs.getInt("position"), rs.getString("action"),
                        rs.getString("expected_result"), rs.getString("result"), rs.getString("actual_result"),
                        rs.getObject("executed_by", UUID.class), rs.getString("executed_by_name"),
                        instant(rs.getTimestamp("executed_at")),
                        evidenceByOwner.getOrDefault(rs.getObject("id", UUID.class), List.of()),
                        defectByOwner.get(rs.getObject("id", UUID.class))));
            }, id);
        List<RunCase> cases = jdbc.query("""
            SELECT c.id, c.position, c.test_case_id, c.case_key, c.title, c.description, c.result, c.actual_result,
                   c.executed_by, u.display_name AS executed_by_name, c.executed_at
              FROM test_run_case c LEFT JOIN app_user u ON u.id = c.executed_by
             WHERE c.run_id = ? ORDER BY c.position
            """, (rs, i) -> {
                UUID caseId = rs.getObject("id", UUID.class);
                List<RunStep> steps = stepsByCase.getOrDefault(caseId, List.of());
                String own = rs.getString("result");
                return new RunCase(caseId, rs.getInt("position"), rs.getObject("test_case_id", UUID.class),
                    rs.getString("case_key"), rs.getString("title"), rs.getString("description"), steps,
                    caseResult(steps, own), rs.getString("actual_result"), rs.getObject("executed_by", UUID.class),
                    rs.getString("executed_by_name"), instant(rs.getTimestamp("executed_at")), evidenceByOwner.getOrDefault(caseId, List.of()),
                    requirementsByCase.getOrDefault(caseId, List.of()), defectByOwner.get(caseId));
            }, id);
        int passed = 0, failed = 0, blocked = 0, notRun = 0;
        for (RunCase c : cases) {
            switch (c.result()) {
                case "PASS" -> passed++;
                case "FAIL" -> failed++;
                case "BLOCKED" -> blocked++;
                default -> notRun++;
            }
        }
        return new RunDetail(rows.get(0), cases, new RunSummary(cases.size(), passed, failed, blocked, notRun,
            jdbc.queryForObject("SELECT count(*) FROM verification WHERE test_run_id = ?", Integer.class, id)));
    }

    private static Instant instant(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    /** Manual runs of one suite or plan, newest first; CI runs are not listed here (they have no suite). */
    public List<Run> listRuns(UUID suiteId, UUID planId, String status) {
        StringBuilder sql = new StringBuilder(RUN_SELECT).append(" WHERE r.kind = 'MANUAL'");
        List<Object> args = new ArrayList<>();
        if (suiteId != null) { sql.append(" AND r.suite_id = ?"); args.add(suiteId); }
        if (planId != null) { sql.append(" AND s.plan_id = ?"); args.add(planId); }
        if (status != null && !status.isBlank()) {
            if (!Set.of("PLANNED", "IN_PROGRESS", "COMPLETED").contains(status)) {
                throw new IllegalArgumentException("Unknown run status: " + status);
            }
            sql.append(" AND r.status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY r.created_at DESC");
        return jdbc.query(sql.toString(), (rs, i) -> run(rs), args.toArray());
    }

    // ---------------------------------------------------------------- helpers

    private void requireExists(String table, UUID id, String message) {
        if (id == null) throw new IllegalArgumentException(message + "(none given)");
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Long.class, id);
        if (n == null || n == 0) throw new NoSuchElementException(message + id);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String str(UUID id) {
        return id == null ? null : id.toString();
    }

    private static Map<String, Object> auditMap(String... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
