package com.vyoog.tasks;

import com.vyoog.identity.AppUser;
import com.vyoog.identity.AppUserRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0340–0349: every derived task, computed fresh on every call from current state
 * — never stored, never authored (VYB-0340 AC2: there is no task table). Each method
 * below is one of the seven conditions VYB-0341–0347 name; {@link #tasksFor} is all of
 * them for one user, which is what My Work (VYB-0370) actually renders.
 *
 * <p>VYB-0838 (D19): {@link #tasksFor} additionally filters out anything the user
 * dismissed via {@link #complete} — the seven queries below are still exactly what they
 * always were; {@code task_completion} is a separate predicate ("did this person
 * dismiss this, at this object's current revision"), not a new kind of task and not a
 * flag stored on one. See {@link #complete}/{@link #reopen}/{@link #completedToday}.
 *

 * <p>"Since"/reason text uses {@code updated_at} as a stand-in for "when this
 * requirement entered its current stage" — the closest thing recorded without a
 * dedicated per-stage timestamp. It's an approximation: a metadata-only edit
 * (VYB-0121) doesn't touch it, but a content revision does, even one that didn't
 * change status. Precise per-stage timing would need reading the most recent
 * {@code requirement.transitioned} audit event instead; not done this session.
 */
@Service
public class TaskService {

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;

    public TaskService(JdbcTemplate jdbc, AppUserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    private static final String BLOCK_JOIN = """
        LEFT JOIN LATERAL (
          SELECT question, assigned_to FROM clarification cl
          WHERE cl.requirement_id = r.id AND cl.state = 'OPEN' AND cl.blocks_task = true
          ORDER BY cl.raised_at ASC LIMIT 1
        ) blk ON true
        """;

    private static java.time.Instant ts(ResultSet rs, String col) throws SQLException {
        var t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static Task requirementTask(ResultSet rs, TaskKind kind, String reason) throws SQLException {
        return new Task(kind, UUID.fromString(rs.getString("id")), rs.getString("key"), reason,
            ts(rs, "updated_at"),
            rs.getString("blocked_question"),
            rs.getString("blocked_owed_by") == null ? null : UUID.fromString(rs.getString("blocked_owed_by")));
    }

    /** VYB-0341: the owner of a requirement carrying an open wording finding. */
    public List<Task> authorWordingTasks(UUID userId) {
        return jdbc.query("""
            SELECT r.id, r.key, r.updated_at, blk.question AS blocked_question, blk.assigned_to AS blocked_owed_by
            FROM requirement r
            JOIN finding f ON f.object_type = 'REQUIREMENT' AND f.object_id = r.id
                          AND f.rule_key = 'ambig' AND f.state = 'OPEN'
            """ + BLOCK_JOIN + """
            WHERE r.owner_id = ? AND r.deleted_at IS NULL
            """,
            (rs, n) -> requirementTask(rs, TaskKind.AUTHOR_WORDING, "Carries an open wording finding"),
            userId);
    }

    /** VYB-0342: each reviewer on an open round they haven't signed. AC2 — observers never appear; this query never selects them. */
    public List<Task> reviewerPendingTasks(UUID userId) {
        return jdbc.query("""
            SELECT rv.id, rv.title FROM review rv
            JOIN review_participant rp ON rp.review_id = rv.id
            WHERE rv.state = 'OPEN' AND rp.user_id = ? AND rp.role = 'REVIEWER' AND rp.signed_at IS NULL
            """,
            (rs, n) -> new Task(TaskKind.REVIEWER_PENDING, UUID.fromString(rs.getString("id")),
                rs.getString("title"), "Review round is open and awaiting your signature", null, null, null),
            userId);
    }

    /** VYB-0343: approvers holding a grant on the round's scope. AC1 — never the item's own owner. */
    public List<Task> approverAwaitingTasks(UUID userId) {
        return jdbc.query("""
            SELECT rv.id, rv.title FROM review rv
            JOIN review_participant rp ON rp.review_id = rv.id
            WHERE rv.state = 'OPEN' AND rp.user_id = ? AND rp.role = 'APPROVER' AND rp.signed_at IS NULL
              AND NOT EXISTS (
                SELECT 1 FROM review_item ri JOIN requirement r2 ON r2.id = ri.requirement_id
                WHERE ri.review_id = rv.id AND r2.owner_id = ?
              )
            """,
            (rs, n) -> new Task(TaskKind.APPROVER_AWAITING, UUID.fromString(rs.getString("id")),
                rs.getString("title"), "Approval round is open and awaiting your signature", null, null, null),
            userId, userId);
    }

    /** VYB-0344: the assigned developer of an approved requirement with no code link at all. */
    public List<Task> developerImplementTasks(UUID userId) {
        return jdbc.query("""
            SELECT r.id, r.key, r.updated_at, blk.question AS blocked_question, blk.assigned_to AS blocked_owed_by
            FROM requirement r
            """ + BLOCK_JOIN + """
            WHERE r.developer_id = ? AND r.status = 'APPROVED' AND r.deleted_at IS NULL
              AND NOT EXISTS (
                SELECT 1 FROM trace_link tl WHERE tl.from_type = 'CODE' AND tl.to_type = 'REQUIREMENT'
                  AND tl.to_id = r.id AND tl.link_type = 'IMPLEMENTS')
            """,
            (rs, n) -> requirementTask(rs, TaskKind.DEVELOPER_IMPLEMENT, "Approved with no code linked"),
            userId);
    }

    /** VYB-0345: code links to an earlier revision than the requirement's current one. AC2 — reason names both. */
    public List<Task> developerReimplementTasks(UUID userId) {
        return jdbc.query("""
            SELECT r.id, r.key, r.revision, maxrev.linked_rev, r.updated_at,
                   blk.question AS blocked_question, blk.assigned_to AS blocked_owed_by
            FROM requirement r
            JOIN (
              SELECT to_id AS req_id, max(reviewed_at_revision) AS linked_rev
              FROM trace_link
              WHERE from_type = 'CODE' AND to_type = 'REQUIREMENT' AND link_type = 'IMPLEMENTS'
              GROUP BY to_id
            ) maxrev ON maxrev.req_id = r.id
            """ + BLOCK_JOIN + """
            WHERE r.developer_id = ? AND r.deleted_at IS NULL AND maxrev.linked_rev < r.revision
            """,
            (rs, n) -> requirementTask(rs, TaskKind.DEVELOPER_REIMPLEMENT,
                "Code links to revision %d; the requirement is now at revision %d"
                    .formatted(rs.getInt("linked_rev"), rs.getInt("revision"))),
            userId);
    }

    /** VYB-0346: an approved requirement with no passing test at its current revision. */
    public List<Task> testerVerifyTasks(UUID userId) {
        return jdbc.query("""
            SELECT r.id, r.key, r.updated_at, blk.question AS blocked_question, blk.assigned_to AS blocked_owed_by
            FROM requirement r
            JOIN requirement_verification_state vs ON vs.id = r.id
            """ + BLOCK_JOIN + """
            WHERE r.tester_id = ? AND r.status = 'APPROVED' AND r.deleted_at IS NULL AND NOT vs.is_verified
            """,
            (rs, n) -> requirementTask(rs, TaskKind.TESTER_VERIFY, "Approved with no passing test at the current revision"),
            userId);
    }

    /** VYB-0347: evidence is stale. AC2 — reason states which revision was last tested. */
    public List<Task> testerReverifyTasks(UUID userId) {
        return jdbc.query("""
            SELECT r.id, r.key, r.revision, r.updated_at,
                   (SELECT max(v.requirement_revision) FROM verification v
                     WHERE v.requirement_id = r.id AND v.result = 'PASS') AS last_tested_revision,
                   blk.question AS blocked_question, blk.assigned_to AS blocked_owed_by
            FROM requirement r
            JOIN requirement_verification_state vs ON vs.id = r.id
            """ + BLOCK_JOIN + """
            WHERE r.tester_id = ? AND r.deleted_at IS NULL AND vs.has_stale_evidence
            """,
            (rs, n) -> requirementTask(rs, TaskKind.TESTER_REVERIFY,
                "Passing evidence is only against revision %d; the requirement is now at revision %d"
                    .formatted(rs.getInt("last_tested_revision"), rs.getInt("revision"))),
            userId);
    }

    public record StalledRequirement(UUID id, String key, String status, long daysInStage, int thresholdDays) {}

    /**
     * VYB-0349: a requirement that's sat at one stage beyond its configured
     * threshold (app_config.stage_stall_threshold_days, per stage — VYB-0334's
     * sibling config). "Sat at" is approximated the same way the rest of this class
     * approximates it — see the class Javadoc — so a content edit that leaves status
     * unchanged still resets the clock.
     */
    public List<StalledRequirement> stalled() {
        return jdbc.query("""
            SELECT r.id, r.key, r.status,
                   EXTRACT(DAY FROM now() - r.updated_at)::bigint AS days_in_stage,
                   (SELECT (cfg.stage_stall_threshold_days ->> r.status)
                      FROM app_config cfg WHERE cfg.id = 1) AS threshold_days
            FROM requirement r
            WHERE r.deleted_at IS NULL
            """,
            (rs, n) -> {
                String thresholdStr = rs.getString("threshold_days");
                return thresholdStr == null ? null : new StalledRequirement(
                    UUID.fromString(rs.getString("id")), rs.getString("key"), rs.getString("status"),
                    rs.getLong("days_in_stage"), Integer.parseInt(thresholdStr));
            }
        ).stream().filter(java.util.Objects::nonNull)
         .filter(s -> s.daysInStage() > s.thresholdDays())
         .toList();
    }

    /** VYB-0757 AC1: "changing a threshold states what it will affect" — how many currently-stalled requirements this candidate value would cover, computed against the live table rather than the persisted config. */
    public long countAtStatusOlderThan(String status, int days) {
        Long n = jdbc.queryForObject("""
            SELECT count(*) FROM requirement
            WHERE status = ? AND deleted_at IS NULL AND EXTRACT(DAY FROM now() - updated_at) > ?
            """, Long.class, status, days);
        return n == null ? 0 : n;
    }

    private List<Task> ownTasks(UUID userId) {
        List<Task> all = new ArrayList<>();
        all.addAll(authorWordingTasks(userId));
        all.addAll(reviewerPendingTasks(userId));
        all.addAll(approverAwaitingTasks(userId));
        all.addAll(developerImplementTasks(userId));
        all.addAll(developerReimplementTasks(userId));
        all.addAll(testerVerifyTasks(userId));
        all.addAll(testerReverifyTasks(userId));
        Set<CompletionKey> dismissed = activeCompletions(userId);
        return all.stream().filter(t -> !dismissed.contains(new CompletionKey(t.kind(), t.objectId()))).toList();
    }

    // ── VYB-0838 (D19): a real completion action on a derived task ───────────────────

    private static final Set<TaskKind> REQUIREMENT_KINDS = EnumSet.of(
        TaskKind.AUTHOR_WORDING, TaskKind.DEVELOPER_IMPLEMENT, TaskKind.DEVELOPER_REIMPLEMENT,
        TaskKind.TESTER_VERIFY, TaskKind.TESTER_REVERIFY);

    private record CompletionKey(TaskKind kind, UUID objectId) {}

    /** Null for a review-scoped kind — a review carries no revision to gate staleness against. */
    private Integer currentRevisionOf(TaskKind kind, UUID objectId) {
        if (!REQUIREMENT_KINDS.contains(kind)) return null;
        return jdbc.query("SELECT revision FROM requirement WHERE id = ?",
            (rs, n) -> rs.getInt("revision"), objectId).stream().findFirst().orElse(null);
    }

    /**
     * (kind, objectId) pairs currently dismissed for this user — a completion counts
     * only while it still matches the object's current revision (or, for a
     * review-scoped kind, unconditionally: reviews carry no revision, so that
     * dismissal holds until the review closes on its own or is explicitly reopened).
     */
    private Set<CompletionKey> activeCompletions(UUID userId) {
        return Set.copyOf(jdbc.query("""
            SELECT tc.kind, tc.object_id
            FROM task_completion tc
            WHERE tc.user_id = ?
              AND (tc.object_revision IS NULL
                   OR tc.object_revision = (SELECT revision FROM requirement WHERE id = tc.object_id))
            """,
            (rs, n) -> new CompletionKey(TaskKind.valueOf(rs.getString("kind")), UUID.fromString(rs.getString("object_id"))),
            userId));
    }

    /** Idempotent: completing an already-completed task at the same revision is a no-op. */
    public void complete(TaskKind kind, UUID objectId, UUID userId) {
        jdbc.update("""
            INSERT INTO task_completion (kind, object_id, object_revision, user_id)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (kind, object_id, object_revision, user_id) DO NOTHING
            """, kind.name(), objectId, currentRevisionOf(kind, objectId), userId);
    }

    /** Removes the completion matching the object's current revision, if any. */
    public void reopen(TaskKind kind, UUID objectId, UUID userId) {
        jdbc.update("""
            DELETE FROM task_completion
            WHERE kind = ? AND object_id = ? AND user_id = ?
              AND object_revision IS NOT DISTINCT FROM ?
            """, kind.name(), objectId, userId, currentRevisionOf(kind, objectId));
    }

    public record CompletedTask(TaskKind kind, UUID objectId, String objectLabel, java.time.Instant completedAt) {}

    /** Everything this user completed since local midnight — My Work's "Closed today" group. */
    public List<CompletedTask> completedToday(UUID userId) {
        List<CompletedTask> out = new ArrayList<>();
        out.addAll(jdbc.query("""
            SELECT tc.kind, tc.object_id, r.key AS label, tc.completed_at
            FROM task_completion tc JOIN requirement r ON r.id = tc.object_id
            WHERE tc.user_id = ? AND tc.completed_at >= date_trunc('day', now())
            """,
            (rs, n) -> new CompletedTask(TaskKind.valueOf(rs.getString("kind")),
                UUID.fromString(rs.getString("object_id")), rs.getString("label"), ts(rs, "completed_at")),
            userId));
        out.addAll(jdbc.query("""
            SELECT tc.kind, tc.object_id, rv.title AS label, tc.completed_at
            FROM task_completion tc JOIN review rv ON rv.id = tc.object_id
            WHERE tc.user_id = ? AND tc.completed_at >= date_trunc('day', now())
            """,
            (rs, n) -> new CompletedTask(TaskKind.valueOf(rs.getString("kind")),
                UUID.fromString(rs.getString("object_id")), rs.getString("label"), ts(rs, "completed_at")),
            userId));
        return out.stream().sorted(Comparator.comparing(CompletedTask::completedAt).reversed()).toList();
    }

    /**
     * VYB-0370: everything derived for one person, across all seven kinds — plus,
     * VYB-0706 AC1, everything derived for anyone who nominated this person as their
     * delegate, each tagged with {@link Task#onBehalfOfUserId} (AC2) so it's never
     * mistaken for the delegate's own work.
     */
    public List<Task> tasksFor(UUID userId) {
        List<Task> all = new ArrayList<>(ownTasks(userId));
        for (AppUser delegator : users.findAllByDelegateId(userId)) {
            for (Task t : ownTasks(delegator.getId())) {
                all.add(t.onBehalfOf(delegator.getId()));
            }
        }
        return all;
    }
}
