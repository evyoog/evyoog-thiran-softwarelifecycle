package com.vyoog.requirements;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * D12: answers "which requirements govern this scope", which stopped being the same
 * question as "which requirements hang off these capabilities".
 *
 * <p>Every scope query in the application goes through here rather than testing
 * {@code requirement.capability_id} itself. A query that tests the column directly gets
 * capability-level requirements and silently drops the product- and application-level
 * ones — which is how a cross-cutting rule ("every screen loads in under two seconds")
 * would fail to reach the brief for the very work it constrains, with nothing to show it
 * had gone missing.
 */
@Service
public class RequirementScopeService {

    private final JdbcTemplate jdbc;

    public RequirementScopeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A requirement in scope, and the level it sits at — the caller needs both. */
    public record Scoped(UUID requirementId, PlacementLevel level) {}

    /**
     * Everything that governs work on these capabilities: the capability-level
     * requirements themselves, plus the application- and product-level ones above them.
     *
     * <p>Ancestors are included because they genuinely constrain the work — a brief that
     * omits an application-wide security rule tells an agent to build something that
     * breaks it. They are returned with their level so the caller can say where each came
     * from rather than presenting a product-wide rule as though somebody wrote it for this
     * capability (Principle 8).
     *
     * @param capabilityIds the capabilities in scope; empty means every capability of the
     *     application, which is what "the whole application" means on the Delivery screen
     */
    public List<Scoped> governing(UUID applicationId, List<UUID> capabilityIds) {
        StringBuilder capClause = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (capabilityIds == null || capabilityIds.isEmpty()) {
            capClause.append("(s.level = 'CAPABILITY' AND s.application_id = ?)");
            args.add(applicationId);
        } else {
            capClause.append("(s.level = 'CAPABILITY' AND s.capability_id = ANY(?::uuid[]))");
            args.add(capabilityIds.stream().map(UUID::toString).toArray(String[]::new));
        }
        args.add(applicationId);
        args.add(applicationId);

        return jdbc.query("""
            SELECT s.requirement_id, s.level
            FROM requirement_scope s
            JOIN requirement r ON r.id = s.requirement_id
            WHERE r.deleted_at IS NULL
              AND ( %s
                 OR (s.level = 'APPLICATION' AND s.application_id = ?)
                 OR (s.level = 'PRODUCT'
                     AND s.product_id = (SELECT product_id FROM application WHERE id = ?)) )
            """.formatted(capClause),
            (rs, n) -> new Scoped(UUID.fromString(rs.getString("requirement_id")),
                PlacementLevel.valueOf(rs.getString("level"))),
            args.toArray());
    }

    /** The placement level of each of these requirements, for callers that already have ids. */
    public Map<UUID, PlacementLevel> levelsOf(List<UUID> requirementIds) {
        if (requirementIds == null || requirementIds.isEmpty()) return Map.of();
        Map<UUID, PlacementLevel> levels = new LinkedHashMap<>();
        jdbc.query("SELECT requirement_id, level FROM requirement_scope WHERE requirement_id = ANY(?::uuid[])",
            rs -> {
                levels.put(UUID.fromString(rs.getString("requirement_id")),
                    PlacementLevel.valueOf(rs.getString("level")));
            },
            (Object) requirementIds.stream().map(UUID::toString).toArray(String[]::new));
        return levels;
    }
}
