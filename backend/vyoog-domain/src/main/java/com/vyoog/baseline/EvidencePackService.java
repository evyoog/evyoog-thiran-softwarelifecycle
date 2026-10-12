package com.vyoog.baseline;

import com.vyoog.platform.audit.AuditService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0948 (F41, D39): the evidence behind a baseline, assembled from what is already recorded. For every requirement the baseline
 * froze, at the revision it froze: the wording, its status at the freeze, who moved it between statuses (the approvals), the design
 * nodes drawn for it, its tests, and the results recorded against that revision. Read only; one {@code REPEATABLE READ} transaction
 * so the sections agree with each other. {@link EvidencePackWriter} turns the result into the bundle.
 *
 * <p>What is and is not "as frozen": the wording is the revision's own (the {@code requirement_revision} row); the status at the
 * freeze is the last recorded move at or before the freeze (a requirement with none was still a draft); approvals and results carry
 * a flag saying whether they were recorded at or before the freeze. Design nodes have no history, so they are as at the export, and
 * the pack says so.
 */
@Service
public class EvidencePackService {

    /** A pack is built in memory; past this many requirements it is refused rather than risk the server. */
    public static final int MAX_REQUIREMENTS = 5000;

    public record BaselineInfo(UUID id, String name, String releaseName, Instant frozenAt, String frozenBy, int gapsAtFreeze, int items) {}

    public record RequirementRow(String key, int frozenRevision, String title, String statement, String type, String priority,
                                 String statusAtFreeze, String currentStatus, int currentRevision, boolean changedSinceFreeze,
                                 boolean deleted, String capability, String application, String product) {}

    public record DesignRow(String requirementKey, String nodeKind, String nodeLabel, String application) {}

    public record TestRow(String requirementKey, String testKey, String testTitle) {}

    public record ResultRow(String requirementKey, int requirementRevision, String testKey, String result, Instant verifiedAt,
                            String buildLabel, String source, boolean atOrBeforeFreeze) {}

    public record StatusChangeRow(String requirementKey, String fromStatus, String toStatus, String actor, Instant at, String reason,
                                  boolean atOrBeforeFreeze) {}

    public record Pack(BaselineInfo baseline, List<RequirementRow> requirements, List<DesignRow> design, List<TestRow> tests,
                       List<ResultRow> results, List<StatusChangeRow> statusChanges) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final EvidencePackWriter writer;

    public EvidencePackService(JdbcTemplate jdbc, AuditService audit, EvidencePackWriter writer) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.writer = writer;
    }

    private static Instant at(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    /** The baseline's evidence, as it stands now. Refuses a baseline that is too large to build in one pack. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Pack assemble(UUID baselineId) {
        BaselineInfo info = jdbc.query("""
            SELECT b.id, b.name, rl.name AS release_name, b.frozen_at, u.display_name AS frozen_by, b.gaps_at_freeze,
                   (SELECT count(*) FROM baseline_item bi WHERE bi.baseline_id = b.id) AS items
            FROM baseline b LEFT JOIN release rl ON rl.id = b.release_id LEFT JOIN app_user u ON u.id = b.frozen_by
            WHERE b.id = ?
            """, rs -> rs.next() ? new BaselineInfo(UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("release_name"),
                at(rs, "frozen_at"), rs.getString("frozen_by"), rs.getInt("gaps_at_freeze"), rs.getInt("items")) : null, baselineId);
        if (info == null) throw new NoSuchElementException("No such baseline");
        if (info.items() > MAX_REQUIREMENTS) {
            throw new IllegalStateException("This baseline holds %d requirements; a pack is limited to %d".formatted(info.items(), MAX_REQUIREMENTS));
        }
        Timestamp frozen = Timestamp.from(info.frozenAt());

        List<RequirementRow> requirements = jdbc.query("""
            SELECT r.key, bi.revision AS frozen_revision, rr.title, rr.statement, rr.type, rr.priority,
                   COALESCE((SELECT a.after->>'status' FROM audit_event a
                              WHERE a.object_type = 'REQUIREMENT' AND a.object_id = r.id AND a.action = 'requirement.transitioned'
                                AND a.occurred_at <= ? ORDER BY a.occurred_at DESC, a.id DESC LIMIT 1), 'DRAFT') AS status_at_freeze,
                   r.status AS current_status, r.revision AS current_revision, r.deleted_at IS NOT NULL AS deleted,
                   cap.name AS capability, app.name AS application, prod.name AS product
            FROM baseline_item bi
            JOIN requirement r ON r.id = bi.requirement_id
            LEFT JOIN requirement_revision rr ON rr.requirement_id = r.id AND rr.revision = bi.revision
            LEFT JOIN capability cap ON cap.id = r.capability_id
            LEFT JOIN application app ON app.id = COALESCE(r.application_id, cap.application_id)
            LEFT JOIN product prod ON prod.id = COALESCE(r.product_id, app.product_id)
            WHERE bi.baseline_id = ?
            ORDER BY r.key
            """, (rs, n) -> new RequirementRow(rs.getString("key"), rs.getInt("frozen_revision"), rs.getString("title"),
                rs.getString("statement"), rs.getString("type"), rs.getString("priority"), rs.getString("status_at_freeze"),
                rs.getString("current_status"), rs.getInt("current_revision"), rs.getInt("current_revision") != rs.getInt("frozen_revision"),
                rs.getBoolean("deleted"), rs.getString("capability"), rs.getString("application"), rs.getString("product")),
            frozen, baselineId);

        List<DesignRow> design = jdbc.query("""
            SELECT r.key, n.kind, n.label, app.name AS application
            FROM baseline_item bi
            JOIN requirement r ON r.id = bi.requirement_id
            JOIN design_node_requirement dnr ON dnr.requirement_id = r.id
            JOIN design_node n ON n.id = dnr.node_id
            JOIN design_flow f ON f.id = n.flow_id
            JOIN application app ON app.id = f.application_id
            WHERE bi.baseline_id = ?
            ORDER BY r.key, n.label, n.id
            """, (rs, n) -> new DesignRow(rs.getString("key"), rs.getString("kind"), rs.getString("label"), rs.getString("application")),
            baselineId);

        List<TestRow> tests = jdbc.query("""
            SELECT r.key, t.key AS test_key, t.title
            FROM baseline_item bi
            JOIN requirement r ON r.id = bi.requirement_id
            JOIN trace_link tl ON tl.to_type = 'REQUIREMENT' AND tl.to_id = r.id AND tl.from_type = 'TEST' AND tl.link_type = 'VERIFIES'
            JOIN test_case t ON t.id = tl.from_id
            WHERE bi.baseline_id = ?
            ORDER BY r.key, t.key
            """, (rs, n) -> new TestRow(rs.getString("key"), rs.getString("test_key"), rs.getString("title")), baselineId);

        List<ResultRow> results = jdbc.query("""
            SELECT r.key, v.requirement_revision, t.key AS test_key, v.result, v.verified_at, tr.build_label, tr.source,
                   v.verified_at <= ? AS before_freeze
            FROM baseline_item bi
            JOIN requirement r ON r.id = bi.requirement_id
            JOIN verification v ON v.requirement_id = r.id AND v.requirement_revision = bi.revision
            LEFT JOIN test_case t ON t.id = v.test_case_id
            LEFT JOIN test_run tr ON tr.id = v.test_run_id
            WHERE bi.baseline_id = ?
            ORDER BY r.key, t.key NULLS FIRST, v.verified_at, v.id
            """, (rs, n) -> new ResultRow(rs.getString("key"), rs.getInt("requirement_revision"), rs.getString("test_key"), rs.getString("result"),
                at(rs, "verified_at"), rs.getString("build_label"), rs.getString("source"), rs.getBoolean("before_freeze")),
            frozen, baselineId);

        List<StatusChangeRow> changes = jdbc.query("""
            SELECT r.key, a.after->>'previousStatus' AS from_status, a.after->>'status' AS to_status, u.display_name AS actor,
                   a.occurred_at, a.after->>'reason' AS reason, a.occurred_at <= ? AS before_freeze
            FROM baseline_item bi
            JOIN requirement r ON r.id = bi.requirement_id
            JOIN audit_event a ON a.object_type = 'REQUIREMENT' AND a.object_id = r.id AND a.action = 'requirement.transitioned'
            LEFT JOIN app_user u ON u.id = a.actor_id
            WHERE bi.baseline_id = ?
            ORDER BY r.key, a.occurred_at, a.id
            """, (rs, n) -> new StatusChangeRow(rs.getString("key"), rs.getString("from_status"), rs.getString("to_status"), rs.getString("actor"),
                at(rs, "occurred_at"), rs.getString("reason"), rs.getBoolean("before_freeze")),
            frozen, baselineId);

        return new Pack(info, requirements, design, tests, results, changes);
    }

    /** A built pack: the file name to offer and the bytes. */
    public record Export(String filename, byte[] bytes) {}

    /** Builds the pack and records, on the audit log, who took it out and what it held. */
    public Export export(UUID baselineId, UUID actor, String actorName) {
        Pack pack = assemble(baselineId);
        Instant now = Instant.now();
        byte[] bytes = writer.build(pack, now, actorName);
        audit.record(actor, "baseline.evidence_pack_exported", "BASELINE", baselineId, null,
            Map.of("name", pack.baseline().name(), "requirements", pack.requirements().size(), "bytes", bytes.length));
        return new Export(writer.filename(pack.baseline(), now), bytes);
    }
}
