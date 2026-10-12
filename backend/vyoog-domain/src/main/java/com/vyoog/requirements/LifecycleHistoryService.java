package com.vyoog.requirements;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0119/0193: who acted, when, at which of the six stages — derived entirely from
 * rows that already exist (requirement, the status history in the audit log, the old
 * review_participant signatures, trace_link, verification,
 * deployment_requirement/deployment), rather than a seventh table recording the same
 * facts a second time. AC2 falls out for free: a stage whose underlying row doesn't
 * exist simply has no entry. AC3: every source table here is either genuinely
 * append-only already (verification, trace_link) or only ever gains a record
 * (the audit log, and the review rounds that D26 froze as read-only history) — nothing this reads is ever un-set.
 *
 * <p>Verification and deployment have no human actor column in this schema (a test
 * run's pass/fail and a deployment event are both system-driven, not a person
 * clicking something) — those two stages report {@code actorId = null} rather than
 * inventing an actor that isn't recorded anywhere.
 */
@Service
public class LifecycleHistoryService {

    private final JdbcTemplate jdbc;

    public LifecycleHistoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Stage(String stage, UUID actorId, Instant occurredAt) {}

    public List<Stage> historyFor(UUID requirementId) {
        List<Stage> stages = new ArrayList<>();

        jdbc.query("SELECT created_by, created_at FROM requirement WHERE id = ?",
            (rs, n) -> {
                String actor = rs.getString("created_by");
                stages.add(new Stage("AUTHORING", actor == null ? null : UUID.fromString(actor),
                    rs.getTimestamp("created_at").toInstant()));
                return null;
            }, requirementId);

        // D26: REVIEW and APPROVAL are the first move into REVIEWED and into APPROVED (the status
        // history), or, on a requirement that went through an old review round, that round's signature —
        // whichever is earlier. Round rows are read-only history now; nothing writes them any more.
        for (String[] stage : new String[][] {{"REVIEW", "REVIEWED", "REVIEWER"}, {"APPROVAL", "APPROVED", "APPROVER"}}) {
            jdbc.query("SELECT actor, at FROM (" + REACHED_SQL + ") reached ORDER BY at ASC LIMIT 1",
                (rs, n) -> {
                    String actor = rs.getString("actor");
                    return stages.add(new Stage(stage[0], actor == null ? null : UUID.fromString(actor),
                        rs.getTimestamp("at").toInstant()));
                }, stage[1], requirementId, stage[2], requirementId);
        }

        jdbc.query("""
            SELECT created_by, created_at FROM trace_link
            WHERE to_type = 'REQUIREMENT' AND to_id = ? AND from_type = 'CODE' AND link_type = 'IMPLEMENTS'
            ORDER BY created_at ASC LIMIT 1
            """,
            (rs, n) -> {
                String actor = rs.getString("created_by");
                stages.add(new Stage("DEVELOPMENT", actor == null ? null : UUID.fromString(actor),
                    rs.getTimestamp("created_at").toInstant()));
                return null;
            }, requirementId);

        jdbc.query("""
            SELECT verified_at FROM verification
            WHERE requirement_id = ? AND result = 'PASS' ORDER BY verified_at ASC LIMIT 1
            """,
            (rs, n) -> stages.add(new Stage("VERIFICATION", null, rs.getTimestamp("verified_at").toInstant())),
            requirementId);

        jdbc.query("""
            SELECT d.deployed_at FROM deployment_requirement dr
            JOIN deployment d ON d.id = dr.deployment_id
            WHERE dr.requirement_id = ? AND d.succeeded = true
            ORDER BY d.deployed_at ASC LIMIT 1
            """,
            (rs, n) -> stages.add(new Stage("DEPLOYMENT", null, rs.getTimestamp("deployed_at").toInstant())),
            requirementId);

        stages.sort(java.util.Comparator.comparing(Stage::occurredAt));
        return stages;
    }

    /** One rollup entry: how many (not-deleted) requirements have reached this stage at all. */
    public record SpineStage(String stage, long count) {}

    /**
     * VYB-0222: the same six stages {@link #historyFor} derives per requirement,
     * rolled up as one count per stage across every requirement at once — a real
     * aggregate query per stage, not {@link #historyFor} called once per requirement
     * (that would be a query per stage *per requirement*, i.e. six times the requirement
     * count; this is six queries total, however many requirements exist).
     */
    public List<SpineStage> spine() {
        List<SpineStage> spine = new ArrayList<>();

        spine.add(new SpineStage("AUTHORING",
            count("SELECT count(*) FROM requirement WHERE deleted_at IS NULL")));

        spine.add(new SpineStage("REVIEW", reached("REVIEWED", "REVIEWER")));
        spine.add(new SpineStage("APPROVAL", reached("APPROVED", "APPROVER")));

        spine.add(new SpineStage("DEVELOPMENT", count("""
            SELECT count(DISTINCT tl.to_id) FROM trace_link tl
            JOIN requirement r ON r.id = tl.to_id AND r.deleted_at IS NULL
            WHERE tl.to_type = 'REQUIREMENT' AND tl.from_type = 'CODE' AND tl.link_type = 'IMPLEMENTS'
            """)));

        spine.add(new SpineStage("VERIFICATION", count("""
            SELECT count(DISTINCT v.requirement_id) FROM verification v
            JOIN requirement r ON r.id = v.requirement_id AND r.deleted_at IS NULL
            WHERE v.result = 'PASS'
            """)));

        spine.add(new SpineStage("DEPLOYMENT", count("""
            SELECT count(DISTINCT dr.requirement_id) FROM deployment_requirement dr
            JOIN deployment d ON d.id = dr.deployment_id
            JOIN requirement r ON r.id = dr.requirement_id AND r.deleted_at IS NULL
            WHERE d.succeeded = true
            """)));

        return spine;
    }

    /** (actor, at) for each way a requirement is known to have reached a status: a recorded move into it, or an old round signature. */
    private static final String REACHED_SQL = """
        SELECT a.actor_id::text AS actor, a.occurred_at AS at FROM audit_event a
        WHERE a.object_type = 'REQUIREMENT' AND a.action = 'requirement.transitioned'
          AND a.after->>'status' = ? AND a.object_id = ?
        UNION ALL
        SELECT rp.user_id::text, rp.signed_at FROM review_participant rp
        JOIN review_item ri ON ri.review_id = rp.review_id
        WHERE rp.role = ? AND rp.signed_at IS NOT NULL AND ri.requirement_id = ?
        """;

    /** How many not-deleted requirements ever reached {@code status} by a recorded move, or hold an old round signature in {@code oldRole}. */
    private long reached(String status, String oldRole) {
        Long n = jdbc.queryForObject("""
            SELECT count(*) FROM requirement r WHERE r.deleted_at IS NULL AND (
              EXISTS (SELECT 1 FROM audit_event a WHERE a.object_type = 'REQUIREMENT' AND a.object_id = r.id
                        AND a.action = 'requirement.transitioned' AND a.after->>'status' = ?)
              OR EXISTS (SELECT 1 FROM review_participant rp JOIN review_item ri ON ri.review_id = rp.review_id
                         WHERE ri.requirement_id = r.id AND rp.role = ? AND rp.signed_at IS NOT NULL))
            """, Long.class, status, oldRole);
        return n == null ? 0 : n;
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}
