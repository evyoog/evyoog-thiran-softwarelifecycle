package com.vyoog.trace;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0943 (F19, D36): the two matrices beside the requirement-by-test coverage matrix ({@link CoverageMatrixService}), for one
 * application. Rows are the application's requirements (placed in one of its capabilities, or on the application itself; not
 * deleted). Nothing is dropped silently: a requirement in no release is a row with no cell, and a defect with no requirement is
 * listed apart, not left out.
 *
 * <ul>
 *   <li><b>Requirement by release</b>: a requirement is in a release's scope ({@code release_scope_item}); it can be in one.</li>
 *   <li><b>Requirement by defect</b>: a defect has at most one requirement ({@code defect.requirement_id}), so each defect column
 *       holds exactly one mark. Open defects by default.</li>
 * </ul>
 */
@Service
public class TraceMatrixService {

    private static final String IN_APPLICATION = """
        r.deleted_at IS NULL AND (r.application_id = ? OR r.capability_id IN (SELECT id FROM capability WHERE application_id = ?))""";

    public record ReleaseColumn(UUID id, String name, String state, long requirements) {}

    /** {@code releaseId} is null when the requirement is in no release. {@code supersededById} is set when a newer version replaced it. */
    public record ReleaseRow(UUID requirementId, String key, String title, String status, UUID supersededById, UUID releaseId) {}

    public record ReleaseMatrix(List<ReleaseColumn> releases, List<ReleaseRow> rows) {}

    public record DefectColumn(UUID id, String key, String title, String severity, String state, UUID requirementId) {}

    public record DefectRow(UUID requirementId, String key, String title, String status, UUID supersededById) {}

    /** {@code untraced} are the open defects with no requirement at all, which belong to no application and so to no row. */
    public record DefectMatrix(List<DefectRow> rows, List<DefectColumn> defects, List<DefectColumn> untraced) {}

    private final JdbcTemplate jdbc;

    public TraceMatrixService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Releases that are not yet released, and any that hold one of this application's requirements; and every requirement's place. */
    public ReleaseMatrix releaseMatrix(UUID applicationId) {
        List<ReleaseRow> rows = jdbc.query("""
            SELECT r.id, r.key, r.title, r.status, r.superseded_by_id, rsi.release_id
            FROM requirement r LEFT JOIN release_scope_item rsi ON rsi.requirement_id = r.id
            WHERE\s""" + IN_APPLICATION + " ORDER BY r.key",
            (rs, n) -> new ReleaseRow(UUID.fromString(rs.getString("id")), rs.getString("key"), rs.getString("title"),
                rs.getString("status"), uuid(rs.getString("superseded_by_id")), uuid(rs.getString("release_id"))),
            applicationId, applicationId);
        List<ReleaseColumn> releases = jdbc.query("""
            SELECT rl.id, rl.name, rl.state,
                   (SELECT count(*) FROM release_scope_item s JOIN requirement r ON r.id = s.requirement_id
                     WHERE s.release_id = rl.id AND\s""" + IN_APPLICATION + """
            ) AS requirements
            FROM release rl
            WHERE rl.state <> 'RELEASED' OR EXISTS (
              SELECT 1 FROM release_scope_item s JOIN requirement r ON r.id = s.requirement_id
               WHERE s.release_id = rl.id AND\s""" + IN_APPLICATION + """
            )
            ORDER BY rl.name
            """,
            (rs, n) -> new ReleaseColumn(UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("state"), rs.getLong("requirements")),
            applicationId, applicationId, applicationId, applicationId);
        return new ReleaseMatrix(releases, rows);
    }

    /** @param includeClosed false (the default) means open defects only; true adds fixed and closed ones */
    public DefectMatrix defectMatrix(UUID applicationId, boolean includeClosed) {
        String states = includeClosed ? "" : " AND d.state = 'OPEN'";
        List<DefectRow> rows = jdbc.query("SELECT r.id, r.key, r.title, r.status, r.superseded_by_id FROM requirement r WHERE "
            + IN_APPLICATION + " ORDER BY r.key",
            (rs, n) -> new DefectRow(UUID.fromString(rs.getString("id")), rs.getString("key"), rs.getString("title"),
                rs.getString("status"), uuid(rs.getString("superseded_by_id"))), applicationId, applicationId);
        List<DefectColumn> defects = jdbc.query("""
            SELECT d.id, d.key, d.title, d.severity, d.state, d.requirement_id
            FROM defect d JOIN requirement r ON r.id = d.requirement_id
            WHERE\s""" + IN_APPLICATION + states + """
             ORDER BY r.key, CASE d.severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END, d.key
            """, (rs, n) -> column(rs), applicationId, applicationId);
        List<DefectColumn> untraced = jdbc.query("""
            SELECT d.id, d.key, d.title, d.severity, d.state, d.requirement_id FROM defect d
            WHERE d.requirement_id IS NULL""" + states + " ORDER BY d.key",
            (rs, n) -> column(rs));
        return new DefectMatrix(rows, defects, untraced);
    }

    private static DefectColumn column(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DefectColumn(UUID.fromString(rs.getString("id")), rs.getString("key"), rs.getString("title"),
            rs.getString("severity"), rs.getString("state"), uuid(rs.getString("requirement_id")));
    }

    private static UUID uuid(String text) {
        return text == null ? null : UUID.fromString(text);
    }
}
