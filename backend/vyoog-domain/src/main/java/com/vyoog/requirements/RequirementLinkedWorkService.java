package com.vyoog.requirements;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0943 (F19, D36): what a requirement is linked to besides other requirements, by name: its tests, the design nodes that
 * cover it, the release it is committed to, and its defects. Each kind keeps its own mechanism (tests are
 * {@code TEST VERIFIES REQUIREMENT} trace links, design is {@code design_node_requirement}, a release is
 * {@code release_scope_item}, a defect has {@code defect.requirement_id}); this only reads them together, so the detail page can
 * show them and offer to add one. Read only.
 */
@Service
public class RequirementLinkedWorkService {

    public record LinkedTest(UUID id, String key, String title) {}
    public record LinkedDesignNode(UUID id, UUID flowId, String kind, String label) {}
    public record LinkedRelease(UUID id, String name, String state) {}
    /** VYB-0949: a risk that is not closed and names this requirement; rating and band as the risk register reads them. */
    public record LinkedRisk(UUID id, String key, String title, int rating, String band, String status) {}
    public record LinkedDefect(UUID id, String key, String title, String severity, String state) {}

    /** {@code applicationId} is the application the requirement sits in (via its capability, or directly); null if it is unplaced or sits on a product. */
    public record LinkedWork(UUID requirementId, UUID applicationId, List<LinkedTest> tests, List<LinkedDesignNode> designNodes,
                             List<LinkedRelease> releases, List<LinkedDefect> defects, List<LinkedRisk> risks) {}

    private final JdbcTemplate jdbc;

    public RequirementLinkedWorkService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public LinkedWork of(UUID requirementId) {
        UUID applicationId = jdbc.query("""
            SELECT COALESCE(r.application_id, c.application_id) AS app
            FROM requirement r LEFT JOIN capability c ON c.id = r.capability_id WHERE r.id = ? AND r.deleted_at IS NULL
            """, rs -> {
                if (!rs.next()) throw new NoSuchElementException("No such requirement");
                String app = rs.getString("app");
                return app == null ? null : UUID.fromString(app);
            }, requirementId);

        List<LinkedTest> tests = jdbc.query("""
            SELECT t.id, t.key, t.title FROM trace_link tl JOIN test_case t ON t.id = tl.from_id
            WHERE tl.from_type = 'TEST' AND tl.to_type = 'REQUIREMENT' AND tl.to_id = ? AND tl.link_type = 'VERIFIES' ORDER BY t.key
            """, (rs, n) -> new LinkedTest(UUID.fromString(rs.getString("id")), rs.getString("key"), rs.getString("title")), requirementId);
        List<LinkedDesignNode> nodes = jdbc.query("""
            SELECT n.id, n.flow_id, n.kind, n.label FROM design_node_requirement dnr JOIN design_node n ON n.id = dnr.node_id
            WHERE dnr.requirement_id = ? ORDER BY n.label
            """, (rs, n) -> new LinkedDesignNode(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("flow_id")),
                rs.getString("kind"), rs.getString("label")), requirementId);
        List<LinkedRelease> releases = jdbc.query("""
            SELECT rl.id, rl.name, rl.state FROM release_scope_item s JOIN release rl ON rl.id = s.release_id
            WHERE s.requirement_id = ? ORDER BY rl.name
            """, (rs, n) -> new LinkedRelease(UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("state")), requirementId);
        List<LinkedDefect> defects = jdbc.query("""
            SELECT d.id, d.key, d.title, d.severity, d.state FROM defect d WHERE d.requirement_id = ?
            ORDER BY CASE d.severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END, d.key
            """, (rs, n) -> new LinkedDefect(UUID.fromString(rs.getString("id")), rs.getString("key"), rs.getString("title"),
                rs.getString("severity"), rs.getString("state")), requirementId);
        List<LinkedRisk> risks = jdbc.query("""
            SELECT k.id, k.key, k.title, k.likelihood * k.impact AS rating, k.status FROM risk_requirement rr JOIN risk k ON k.id = rr.risk_id
            WHERE rr.requirement_id = ? AND k.status <> 'CLOSED' ORDER BY k.likelihood * k.impact DESC, k.key
            """, (rs, n) -> new LinkedRisk(UUID.fromString(rs.getString("id")), rs.getString("key"), rs.getString("title"), rs.getInt("rating"),
                com.vyoog.risk.RiskScale.band(rs.getInt("rating")), rs.getString("status")), requirementId);
        return new LinkedWork(requirementId, applicationId, tests, nodes, releases, defects, risks);
    }
}
