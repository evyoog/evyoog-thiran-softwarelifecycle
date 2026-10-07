package com.vyoog.defect;

import com.vyoog.notify.NotificationService;
import com.vyoog.platform.audit.AuditService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0931: the rest of a defect's life after it is raised (VYB-0320) and classified or closed (VYB-0321).
 *
 * <p>States: OPEN, then FIXED (by the developer), then CLOSED (a root cause is required, {@link DefectService#close});
 * a FIXED or CLOSED defect can be reopened, with a reason; closing straight from OPEN is still allowed. Every move is a
 * recorded row ({@code defect_transition}) and an audit event. A closed defect is not edited: reopen it first.
 *
 * <p>Besides the moves: edit (title, severity, where it was found), assign (developer and tester), link (one optional
 * test case, test run and release), and comment (append-only). Who may do each is the caller's rule.
 */
@Service
public class DefectLifecycleService {

    public record Transition(UUID id, DefectState from, DefectState to, String reason, UUID changedBy, String changedByName, Instant changedAt) {}

    public record Comment(UUID id, UUID authorId, String authorName, String body, Instant createdAt) {}

    public record Ref(UUID id, String label) {}

    /** A defect with everything the detail panel shows. {@code raisedFrom*} are set when it was raised from a failed run step or case (V044). */
    public record Detail(Defect defect, String requirementKey, String requirementTitle, String developerName, String testerName,
                          Ref testCase, Ref testRun, Ref release, UUID raisedFromRunId, UUID raisedFromRunStepId,
                          UUID raisedFromRunCaseId, String raisedFromTestKey, List<Transition> transitions) {}

    /** One row of the list, with names so a screen shows people and requirements rather than ids. */
    public record Row(Defect defect, String requirementKey, String developerName, String testerName, UUID releaseId, String releaseName) {}

    public record Filter(String state, DefectSeverity severity, UUID releaseId, UUID assignedTo, String q) {}

    private final DefectRepository defects;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final NotificationService notifications;

    public DefectLifecycleService(DefectRepository defects, JdbcTemplate jdbc, AuditService audit, NotificationService notifications) {
        this.defects = defects;
        this.jdbc = jdbc;
        this.audit = audit;
        this.notifications = notifications;
    }

    private Defect require(UUID id) {
        return defects.findById(id).orElseThrow(() -> new NoSuchElementException("No such defect: " + id));
    }

    // ------------------------------------------------------------------ moves

    /** OPEN to FIXED, by the developer (the caller checks who). The tester, if one is routed, is told to verify it. */
    @Transactional
    public Defect markFixed(UUID id, String note, UUID actor) {
        Defect d = require(id);
        if (d.getState() != DefectState.OPEN) {
            throw new IllegalStateException("Only an open defect can be marked fixed; this one is " + d.getState().name().toLowerCase());
        }
        d.markFixed();
        defects.saveAndFlush(d);
        record(d, DefectState.OPEN, DefectState.FIXED, blankToNull(note), actor);
        if (d.getTesterId() != null) {
            notifications.notify(d.getTesterId(), "info", "defect-fixed", "Defect " + d.getKey() + " is marked fixed",
                "Please verify the fix", "/defects/" + d.getId());
        }
        return d;
    }

    /** FIXED or CLOSED back to OPEN; a reason is required. The developer, if one is routed, is told. */
    @Transactional
    public Defect reopen(UUID id, String reason, UUID actor) {
        Defect d = require(id);
        String clean = blankToNull(reason);
        if (clean == null) throw new IllegalArgumentException("Reopening a defect needs a reason");
        if (d.getState() == DefectState.OPEN) throw new IllegalStateException("This defect is already open");
        DefectState from = d.getState();
        d.reopen();
        defects.saveAndFlush(d);
        record(d, from, DefectState.OPEN, clean, actor);
        if (d.getDeveloperId() != null) {
            notifications.notify(d.getDeveloperId(), "warn", "defect-reopened", "Defect " + d.getKey() + " was reopened", clean,
                "/defects/" + d.getId());
        }
        return d;
    }

    private void record(Defect d, DefectState from, DefectState to, String reason, UUID actor) {
        jdbc.update("INSERT INTO defect_transition (defect_id, from_state, to_state, reason, changed_by) VALUES (?, ?, ?, ?, ?)",
            d.getId(), from.name(), to.name(), reason, actor);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("state", to.name());
        after.put("reason", reason);
        audit.record(actor, "defect." + (to == DefectState.FIXED ? "fixed" : "reopened"), "DEFECT", d.getId(),
            Map.of("state", from.name()), after);
    }

    // ----------------------------------------------------------------- fields

    /** Title, severity and where it was found. Refused on a closed defect (reopen it first). */
    @Transactional
    public Defect edit(UUID id, String title, DefectSeverity severity, FoundIn foundIn, UUID actor) {
        Defect d = require(id);
        if (d.getState() == DefectState.CLOSED) throw new IllegalStateException("A closed defect cannot be edited; reopen it first");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("A defect needs a title");
        if (severity == null || foundIn == null) throw new IllegalArgumentException("Severity and where it was found are required");
        Map<String, Object> before = Map.of("title", d.getTitle(), "severity", d.getSeverity().name(), "foundIn", d.getFoundIn().name());
        d.edit(title.strip(), severity, foundIn);
        defects.saveAndFlush(d);
        audit.record(actor, "defect.edited", "DEFECT", d.getId(), before,
            Map.of("title", d.getTitle(), "severity", severity.name(), "foundIn", foundIn.name()));
        return d;
    }

    /**
     * Who the defect is routed to: replaces both (null means nobody). A person newly assigned is told; one who was
     * already assigned is not told again. Both must exist.
     */
    @Transactional
    public Defect assign(UUID id, UUID developerId, UUID testerId, UUID actor) {
        Defect d = require(id);
        requireUser(developerId);
        requireUser(testerId);
        UUID oldDeveloper = d.getDeveloperId(), oldTester = d.getTesterId();
        d.assign(developerId, testerId);
        defects.saveAndFlush(d);
        audit.record(actor, "defect.assigned", "DEFECT", d.getId(), people(oldDeveloper, oldTester), people(developerId, testerId));
        if (developerId != null && !developerId.equals(oldDeveloper)) {
            notifications.notify(developerId, "warn", "defect-routed-developer", "Defect " + d.getKey() + " routed to you", d.getTitle(),
                "/defects/" + d.getId());
        }
        if (testerId != null && !testerId.equals(oldTester)) {
            notifications.notify(testerId, "warn", "defect-routed-tester", "Defect " + d.getKey() + " is yours to verify", d.getTitle(),
                "/defects/" + d.getId());
        }
        return d;
    }

    /** Nullable values, so not {@code Map.of}. */
    private static Map<String, Object> people(UUID developerId, UUID testerId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("developerId", str(developerId));
        m.put("testerId", str(testerId));
        return m;
    }

    private void requireUser(UUID userId) {
        if (userId == null) return;
        Long n = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id = ?", Long.class, userId);
        if (n == null || n == 0) throw new NoSuchElementException("No such user: " + userId);
    }

    /** One optional link each to a test case, a test run and a release: replaces all three (null clears one). */
    @Transactional
    public void link(UUID id, UUID testCaseId, UUID testRunId, UUID releaseId, UUID actor) {
        Defect d = require(id);
        requireExists("test_case", testCaseId, "No such test case: ");
        requireExists("test_run", testRunId, "No such test run: ");
        requireExists("release", releaseId, "No such release: ");
        Map<String, Object> before = new LinkedHashMap<>();
        jdbc.query("SELECT test_case_id, test_run_id, release_id FROM defect WHERE id = ?", rs -> {
            before.put("testCaseId", rs.getString("test_case_id"));
            before.put("testRunId", rs.getString("test_run_id"));
            before.put("releaseId", rs.getString("release_id"));
        }, d.getId());
        jdbc.update("UPDATE defect SET test_case_id = ?, test_run_id = ?, release_id = ? WHERE id = ?", testCaseId, testRunId, releaseId, d.getId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("testCaseId", str(testCaseId));
        after.put("testRunId", str(testRunId));
        after.put("releaseId", str(releaseId));
        audit.record(actor, "defect.linked", "DEFECT", d.getId(), before, after);
    }

    private void requireExists(String table, UUID id, String message) {
        if (id == null) return;
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Long.class, id);
        if (n == null || n == 0) throw new NoSuchElementException(message + id);
    }

    // --------------------------------------------------------------- comments

    @Transactional
    public Comment comment(UUID id, String body, UUID author) {
        Defect d = require(id);
        String clean = blankToNull(body);
        if (clean == null) throw new IllegalArgumentException("A comment cannot be empty");
        if (clean.length() > 4000) throw new IllegalArgumentException("A comment is at most 4000 characters");
        UUID commentId = jdbc.queryForObject("INSERT INTO defect_comment (defect_id, author_id, body) VALUES (?, ?, ?) RETURNING id",
            UUID.class, d.getId(), author, clean);
        audit.record(author, "defect.commented", "DEFECT", d.getId(), null, Map.of("commentId", commentId.toString()));
        return comments(id).stream().filter(c -> c.id().equals(commentId)).findFirst().orElseThrow();
    }

    public List<Comment> comments(UUID id) {
        require(id);
        return jdbc.query("""
            SELECT c.id, c.author_id, u.display_name, c.body, c.created_at
              FROM defect_comment c LEFT JOIN app_user u ON u.id = c.author_id
             WHERE c.defect_id = ? ORDER BY c.created_at, c.id
            """, (rs, i) -> new Comment(rs.getObject("id", UUID.class), rs.getObject("author_id", UUID.class), rs.getString("display_name"),
                rs.getString("body"), rs.getTimestamp("created_at").toInstant()), id);
    }

    // ------------------------------------------------------------------ reads

    public Detail detail(UUID id) {
        Defect d = require(id);
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT r.key AS req_key, r.title AS req_title, dev.display_name AS dev_name, tst.display_name AS tester_name,
                   d.test_case_id, tc.key AS tc_key, tc.title AS tc_title,
                   d.test_run_id, run.build_label, run.kind AS run_kind, run.status AS run_status,
                   d.release_id, rel.name AS rel_name,
                   d.raised_from_run_step_id, d.raised_from_run_case_id,
                   coalesce(stc.run_id, cc.run_id) AS from_run_id,
                   coalesce(stc.case_key, cc.case_key) AS from_test_key
              FROM defect d
              LEFT JOIN requirement r ON r.id = d.requirement_id
              LEFT JOIN app_user dev ON dev.id = d.developer_id
              LEFT JOIN app_user tst ON tst.id = d.tester_id
              LEFT JOIN test_case tc ON tc.id = d.test_case_id
              LEFT JOIN test_run run ON run.id = d.test_run_id
              LEFT JOIN release rel ON rel.id = d.release_id
              LEFT JOIN test_run_step rs ON rs.id = d.raised_from_run_step_id
              LEFT JOIN test_run_case stc ON stc.id = rs.run_case_id
              LEFT JOIN test_run_case cc ON cc.id = d.raised_from_run_case_id
             WHERE d.id = ?
            """, d.getId());
        List<Transition> transitions = jdbc.query("""
            SELECT t.id, t.from_state, t.to_state, t.reason, t.changed_by, u.display_name, t.changed_at
              FROM defect_transition t LEFT JOIN app_user u ON u.id = t.changed_by
             WHERE t.defect_id = ? ORDER BY t.changed_at, t.id
            """, (rs, i) -> new Transition(rs.getObject("id", UUID.class), DefectState.valueOf(rs.getString("from_state")),
                DefectState.valueOf(rs.getString("to_state")), rs.getString("reason"), rs.getObject("changed_by", UUID.class),
                rs.getString("display_name"), rs.getTimestamp("changed_at").toInstant()), d.getId());
        return new Detail(d, (String) row.get("req_key"), (String) row.get("req_title"), (String) row.get("dev_name"), (String) row.get("tester_name"),
            ref(row.get("test_case_id"), row.get("tc_key") == null ? null : row.get("tc_key") + " " + row.get("tc_title")),
            ref(row.get("test_run_id"), row.get("test_run_id") == null ? null
                : (row.get("build_label") == null ? "Run" : row.get("build_label")) + " (" + String.valueOf(row.get("run_kind")).toLowerCase()
                    + ", " + String.valueOf(row.get("run_status")).toLowerCase().replace('_', ' ') + ")"),
            ref(row.get("release_id"), (String) row.get("rel_name")),
            uuid(row.get("from_run_id")), uuid(row.get("raised_from_run_step_id")), uuid(row.get("raised_from_run_case_id")),
            (String) row.get("from_test_key"), transitions);
    }

    private static Ref ref(Object id, String label) {
        return id == null ? null : new Ref(uuid(id), label);
    }

    private static UUID uuid(Object o) {
        return o == null ? null : (o instanceof UUID u ? u : UUID.fromString(o.toString()));
    }

    /**
     * The defect list. {@code state} is OPEN, FIXED, CLOSED or ALL (default OPEN). {@code assignedTo} matches the developer or the
     * tester. {@code q} matches the key or title (wildcards literal). Most severe first, then newest.
     */
    public Page<Row> list(Filter f, Pageable pageable) {
        StringBuilder where = new StringBuilder(" WHERE true");
        List<Object> args = new ArrayList<>();
        String state = f.state() == null || f.state().isBlank() ? "OPEN" : f.state().toUpperCase(java.util.Locale.ROOT);
        if (!state.equals("ALL")) {
            where.append(" AND d.state = ?");
            args.add(DefectState.valueOf(state).name());
        }
        if (f.severity() != null) { where.append(" AND d.severity = ?"); args.add(f.severity().name()); }
        if (f.releaseId() != null) { where.append(" AND d.release_id = ?"); args.add(f.releaseId()); }
        if (f.assignedTo() != null) { where.append(" AND (d.developer_id = ? OR d.tester_id = ?)"); args.add(f.assignedTo()); args.add(f.assignedTo()); }
        if (f.q() != null && !f.q().isBlank()) {
            where.append(" AND (d.key ILIKE ? OR d.title ILIKE ?)");
            String like = "%" + f.q().strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM defect d" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageable.getPageSize());
        pageArgs.add(pageable.getOffset());
        List<UUID> ids = jdbc.queryForList("SELECT d.id FROM defect d" + where
            + " ORDER BY CASE d.severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END, d.raised_at DESC, d.key"
            + " LIMIT ? OFFSET ?", UUID.class, pageArgs.toArray());
        List<Row> rows = new ArrayList<>();
        for (UUID id : ids) {
            Defect d = require(id);
            Map<String, Object> names = jdbc.queryForMap("""
                SELECT r.key AS req_key, dev.display_name AS dev_name, tst.display_name AS tester_name, d.release_id, rel.name AS rel_name
                  FROM defect d LEFT JOIN requirement r ON r.id = d.requirement_id
                  LEFT JOIN app_user dev ON dev.id = d.developer_id LEFT JOIN app_user tst ON tst.id = d.tester_id
                  LEFT JOIN release rel ON rel.id = d.release_id WHERE d.id = ?""", id);
            rows.add(new Row(d, (String) names.get("req_key"), (String) names.get("dev_name"), (String) names.get("tester_name"),
                uuid(names.get("release_id")), (String) names.get("rel_name")));
        }
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String str(UUID id) {
        return id == null ? null : id.toString();
    }
}
