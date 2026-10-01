package com.vyoog.search;

import com.vyoog.identity.AccessGrant;
import com.vyoog.identity.AccessGrantRepository;
import com.vyoog.identity.ScopeType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0766 / VYB-0908 (F09): one field, four kinds of thing, grouped. Results respect the caller's grants.
 *
 * <p><b>The rule</b> (spec §4.4: every role includes "Read"): a person sees what falls under a scope
 * where they hold <em>any</em> active grant (not revoked, not expired), at or above the item:
 * <ul>
 *   <li>a PLATFORM grant sees everything;</li>
 *   <li>a PRODUCT grant sees that product's applications, their capabilities and the requirements
 *       placed anywhere beneath (a requirement is placed at a capability, an application or a product);</li>
 *   <li>an APP grant, its capabilities and requirements; a CAPABILITY grant, that capability's requirements;</li>
 *   <li>a requirement placed nowhere yet is visible to its creator and owner, and to PLATFORM grant holders;</li>
 *   <li>glossary terms are the shared vocabulary: visible to anyone holding any active grant;</li>
 *   <li>a finding is visible if the thing it is about is: a requirement's, a capability's, or a trace link's
 *       source requirement. Findings about anything else (clauses) are for PLATFORM grant holders only.</li>
 * </ul>
 * A person with no active grant finds nothing. RELEASE-scoped grants do not widen search. Only search
 * is scoped by this: the other read endpoints are not, yet.
 */
@Service
public class SearchService {

    private static final int LIMIT_PER_KIND = 8;

    private final JdbcTemplate jdbc;
    private final AccessGrantRepository grants;

    public SearchService(JdbcTemplate jdbc, AccessGrantRepository grants) {
        this.jdbc = jdbc;
        this.grants = grants;
    }

    public record Result(String kind, UUID id, String label, String detail) {}

    /** What a person's active grants cover. */
    record Reach(boolean platform, boolean any, String[] products, String[] apps, String[] capabilities) {}

    Reach reachOf(UUID userId) {
        List<AccessGrant> active = grants.findAllByUserId(userId).stream().filter(AccessGrant::isActive).toList();
        boolean platform = active.stream().anyMatch(g -> g.getScopeType() == ScopeType.PLATFORM);
        return new Reach(platform, !active.isEmpty(), idsOf(active, ScopeType.PRODUCT),
            idsOf(active, ScopeType.APP), idsOf(active, ScopeType.CAPABILITY));
    }

    private static String[] idsOf(List<AccessGrant> active, ScopeType type) {
        return active.stream().filter(g -> g.getScopeType() == type).map(g -> g.getScopeId().toString())
            .distinct().toArray(String[]::new);
    }

    public List<Result> search(String q, UUID userId) {
        if (q == null || q.isBlank()) return List.of();
        Reach reach = reachOf(userId);
        if (!reach.any()) return List.of();
        String pattern = "%" + q.trim() + "%";
        List<Result> out = new ArrayList<>();

        // Everything a non-platform person can see is derived from three id lists, expanded downwards by
        // two CTEs: applications under granted products, capabilities under granted or reachable apps.
        // The ids come from our own tables as UUID objects and are inlined as quoted literals (a UUID's
        // text form cannot carry SQL), which keeps the bound parameters to the search pattern and limit.
        String scope = reach.platform() ? "" : """
            WITH vis_app AS (SELECT id FROM application WHERE id IN (%s) OR product_id IN (%s)),
                 vis_cap AS (SELECT id FROM capability WHERE id IN (%s) OR application_id IN (SELECT id FROM vis_app))
            """.formatted(literals(reach.apps()), literals(reach.products()), literals(reach.capabilities()));
        String me = "'" + userId + "'";

        String requirementVisible = reach.platform() ? "TRUE" : """
            (r.product_id IN (%s) OR r.application_id IN (SELECT id FROM vis_app)
             OR r.capability_id IN (SELECT id FROM vis_cap)
             OR (r.product_id IS NULL AND r.application_id IS NULL AND r.capability_id IS NULL
                 AND (r.created_by = %s OR r.owner_id = %s)))
            """.formatted(literals(reach.products()), me, me);

        out.addAll(jdbc.query(scope + """
            SELECT r.id, r.key, r.title FROM requirement r
            WHERE r.deleted_at IS NULL AND (r.title ILIKE ? OR r.key ILIKE ? OR r.statement ILIKE ?)
              AND %s
            ORDER BY r.key LIMIT ?
            """.formatted(requirementVisible),
            (rs, n) -> new Result("REQUIREMENT", UUID.fromString(rs.getString("id")),
                rs.getString("key") + " " + rs.getString("title"), null),
            pattern, pattern, pattern, LIMIT_PER_KIND));

        String capabilityVisible = reach.platform() ? "TRUE" : "c.id IN (SELECT id FROM vis_cap)";
        out.addAll(jdbc.query(scope + """
            SELECT c.id, c.name, c.code FROM capability c
            WHERE c.archived_at IS NULL AND c.name ILIKE ? AND %s
            ORDER BY c.name LIMIT ?
            """.formatted(capabilityVisible),
            (rs, n) -> new Result("CAPABILITY", UUID.fromString(rs.getString("id")),
                rs.getString("name"), rs.getString("code")),
            pattern, LIMIT_PER_KIND));

        // glossary: the shared vocabulary; the caller already holds at least one grant
        out.addAll(jdbc.query("""
            SELECT id, term, definition FROM glossary_term WHERE term ILIKE ? ORDER BY term LIMIT ?
            """,
            (rs, n) -> new Result("GLOSSARY_TERM", UUID.fromString(rs.getString("id")),
                rs.getString("term"), rs.getString("definition")),
            pattern, LIMIT_PER_KIND));

        // findings: visible with the thing they are about; anything else (clauses) only to PLATFORM
        String findingVisible = reach.platform() ? "TRUE" : """
            (   (f.object_type = 'REQUIREMENT' AND EXISTS (
                    SELECT 1 FROM requirement r WHERE r.id = f.object_id AND r.deleted_at IS NULL AND %1$s))
             OR (f.object_type = 'CAPABILITY' AND f.object_id IN (SELECT id FROM vis_cap))
             OR (f.object_type = 'TRACE_LINK' AND EXISTS (
                    SELECT 1 FROM trace_link l JOIN requirement r ON r.id = l.from_id
                    WHERE l.id = f.object_id AND l.from_type = 'REQUIREMENT' AND r.deleted_at IS NULL AND %1$s)))
            """.formatted(requirementVisible);
        out.addAll(jdbc.query(scope + """
            SELECT f.id, f.title, f.rule_key FROM finding f
            WHERE f.state = 'OPEN' AND f.title ILIKE ? AND %s
            ORDER BY f.first_seen_at DESC LIMIT ?
            """.formatted(findingVisible),
            (rs, n) -> new Result("FINDING", UUID.fromString(rs.getString("id")),
                rs.getString("title"), rs.getString("rule_key")),
            pattern, LIMIT_PER_KIND));

        return out;
    }

    /** A comma-separated list of quoted uuid literals; an empty list becomes the nil uuid, which matches nothing. */
    private static String literals(String[] ids) {
        if (ids.length == 0) return "'00000000-0000-0000-0000-000000000000'";
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            UUID.fromString(id); // throws if it is not a uuid: nothing else can be inlined
            if (sb.length() > 0) sb.append(',');
            sb.append('\'').append(id).append('\'');
        }
        return sb.toString();
    }
}
