package com.vyoog.requirements;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0119/0193: who acted, when, at which of the six stages — derived entirely from
 * rows that already exist (requirement, review_participant, trace_link, verification,
 * deployment_requirement/deployment), rather than a seventh table recording the same
 * facts a second time. AC2 falls out for free: a stage whose underlying row doesn't
 * exist simply has no entry. AC3: every source table here is either genuinely
 * append-only already (verification, trace_link) or only ever gains a signature
 * (review_participant.signed_at) — nothing this reads is ever un-set.
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

        jdbc.query("""
            SELECT rp.user_id, rp.signed_at FROM review_participant rp
            JOIN review_item ri ON ri.review_id = rp.review_id
            WHERE ri.requirement_id = ? AND rp.role = 'REVIEWER' AND rp.signed_at IS NOT NULL
            ORDER BY rp.signed_at ASC LIMIT 1
            """,
            (rs, n) -> stages.add(new Stage("REVIEW", UUID.fromString(rs.getString("user_id")),
                rs.getTimestamp("signed_at").toInstant())),
            requirementId);

        jdbc.query("""
            SELECT rp.user_id, rp.signed_at FROM review_participant rp
            JOIN review_item ri ON ri.review_id = rp.review_id
            WHERE ri.requirement_id = ? AND rp.role = 'APPROVER' AND rp.signed_at IS NOT NULL
            ORDER BY rp.signed_at ASC LIMIT 1
            """,
            (rs, n) -> stages.add(new Stage("APPROVAL", UUID.fromString(rs.getString("user_id")),
                rs.getTimestamp("signed_at").toInstant())),
            requirementId);

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

        spine.add(new SpineStage("REVIEW", count("""
            SELECT count(DISTINCT ri.requirement_id) FROM review_participant rp
            JOIN review_item ri ON ri.review_id = rp.review_id
            JOIN requirement r ON r.id = ri.requirement_id AND r.deleted_at IS NULL
            WHERE rp.role = 'REVIEWER' AND rp.signed_at IS NOT NULL
            """)));

        spine.add(new SpineStage("APPROVAL", count("""
            SELECT count(DISTINCT ri.requirement_id) FROM review_participant rp
            JOIN review_item ri ON ri.review_id = rp.review_id
            JOIN requirement r ON r.id = ri.requirement_id AND r.deleted_at IS NULL
            WHERE rp.role = 'APPROVER' AND rp.signed_at IS NOT NULL
            """)));

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

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}
