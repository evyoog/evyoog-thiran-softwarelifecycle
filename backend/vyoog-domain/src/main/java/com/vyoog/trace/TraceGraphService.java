package com.vyoog.trace;

import com.vyoog.detection.DetectionSweepService;
import com.vyoog.requirements.RequirementRepository;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the trace graph: typed links (VYB-0140), the materialised closure
 * (VYB-0142/0143), bounded traversal (VYB-0144/0145) and the coverage projection
 * (VYB-0146).
 *
 * <p>Per CLAUDE.md's style rule, recursive CTEs go through {@link JdbcTemplate}, never
 * JPQL — Hibernate has no useful way to express a bounded recursive walk, and forcing
 * one would just hide a raw query behind an unhelpfully abstracted one.
 */
@Service
public class TraceGraphService {

    private static final Logger log = LoggerFactory.getLogger(TraceGraphService.class);

    /** VYB-0144 AC2: the bound is at most 12, whatever the caller asks for. */
    public static final int MAX_DEPTH = 12;

    private final TraceLinkRepository links;
    private final RequirementRepository requirements;
    private final JdbcTemplate jdbc;
    private final DetectionSweepService detection;

    public TraceGraphService(TraceLinkRepository links, RequirementRepository requirements, JdbcTemplate jdbc,
                              DetectionSweepService detection) {
        this.links = links;
        this.requirements = requirements;
        this.jdbc = jdbc;
        this.detection = detection;
    }

    /** VYB-0161: a link changing is exactly what "orphan"/"nodesign"/"suspect" read. */
    private void rescan(UUID objectId) {
        try {
            links.flush();
            detection.rescanObject(objectId);
        } catch (Exception e) {
            log.warn("[detection] bounded rescan failed for {}: {}", objectId, e.getMessage());
        }
    }

    // ── Links ────────────────────────────────────────────────────────────────

    /** The direct links touching this object, split by direction — for display/management. */
    public DirectLinks linksFor(TraceObjectType type, UUID id) {
        return new DirectLinks(links.findAllByFromTypeAndFromId(type, id), links.findAllByToTypeAndToId(type, id));
    }

    public record DirectLinks(List<TraceLink> outgoing, List<TraceLink> incoming) {}

    @Transactional
    public TraceLink createLink(TraceObjectType fromType, UUID fromId, TraceObjectType toType, UUID toId,
                                 TraceLinkType linkType, UUID actorId) {
        // VYB-0140 AC1: a duplicate link is rejected — checked explicitly rather than
        // relying only on the DB's unique constraint, so the caller gets a clear 409
        // instead of a generic constraint-violation message.
        if (links.existsByFromTypeAndFromIdAndToTypeAndToIdAndLinkType(fromType, fromId, toType, toId, linkType)) {
            throw new IllegalStateException("That link already exists");
        }
        // AC2: both endpoints are validated to exist, for the types that have a table
        // to check against — see TraceObjectType's per-constant notes for the gap.
        requireExists(fromType, fromId);
        requireExists(toType, toId);

        TraceLink link = new TraceLink(fromType, fromId, toType, toId, linkType, actorId);
        // VYB-0141 AC1: creating a link records the current upstream revision — treating
        // creation as an implicit first review, not leaving the field null until someone
        // explicitly reviews it.
        currentRevisionIfRequirement(fromType, fromId).ifPresent(link::markReviewedAt);
        // saveAndFlush, not save: recomputeClosure() reads trace_link with JdbcTemplate, which does not
        // wait for Hibernate's deferred INSERT, so with save() the closure was always one link behind.
        links.saveAndFlush(link);
        recomputeClosure();
        if (toType == TraceObjectType.REQUIREMENT) rescan(toId);
        if (fromType == TraceObjectType.REQUIREMENT) rescan(fromId);
        return link;
    }

    @Transactional
    public void deleteLink(UUID id) {
        TraceLink link = links.findById(id).orElseThrow(NoSuchElementException::new);
        links.deleteById(id);
        links.flush(); // same reason as createLink: the closure query must see the delete
        recomputeClosure(); // VYB-0142 AC2/AC3: removes exactly the justified rows, no orphans
        if (link.getToType() == TraceObjectType.REQUIREMENT) rescan(link.getToId());
        if (link.getFromType() == TraceObjectType.REQUIREMENT) rescan(link.getFromId());
    }

    /**
     * VYB-0141 AC2: the only other way {@code reviewedAtRevision} ever changes — looks
     * up the upstream's *current* revision itself rather than trusting a caller-supplied
     * number, so a stale client can't rubber-stamp a link as reviewed against a revision
     * that isn't actually current.
     */
    @Transactional
    public TraceLink reviewLink(UUID linkId) {
        TraceLink link = links.findById(linkId).orElseThrow(NoSuchElementException::new);
        int currentRevision = currentRevisionIfRequirement(link.getFromType(), link.getFromId())
            .orElseThrow(() -> new IllegalStateException(
                "Only a requirement-sourced link carries a revision to review against today"));
        link.markReviewedAt(currentRevision);
        TraceLink saved = links.save(link);
        rescan(saved.getId()); // VYB-0161: SuspectLinkDetector.scanOne accepts a link id directly
        return saved;
    }

    private Optional<Integer> currentRevisionIfRequirement(TraceObjectType type, UUID id) {
        if (type != TraceObjectType.REQUIREMENT) return Optional.empty();
        return requirements.findById(id).map(r -> r.getRevision());
    }

    private void requireExists(TraceObjectType type, UUID id) {
        type.backingTable().ifPresent(table -> {
            Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM " + table + " WHERE id = ?)", Boolean.class, id);
            if (exists == null || !exists) {
                throw new IllegalArgumentException("No such " + type + ": " + id);
            }
        });
    }

    // ── Closure (VYB-0142/0143) ──────────────────────────────────────────────

    /**
     * The recursive computation shared by {@link #recomputeClosure()} and
     * {@link #checkClosureDrift()} — one definition, so "rebuilding produces an
     * identical table" (VYB-0143 AC1) is true by construction rather than by keeping
     * two queries in sync by hand.
     *
     * <p>Cycle-safety: the {@code visited} array excludes any node already on the
     * current path, and depth is separately bounded at {@link #MAX_DEPTH} — together
     * they guarantee termination on a cyclic graph (VYB-0144) without needing the
     * visited-array check alone to be perfectly airtight.
     */
    private static final String COMPUTE_CLOSURE_SQL = """
        WITH RECURSIVE reach(ancestor_type, ancestor_id, descendant_type, descendant_id, depth, visited) AS (
          SELECT from_type, from_id, to_type, to_id, 1,
                 ARRAY[from_type || ':' || from_id::text, to_type || ':' || to_id::text]
          FROM trace_link
          UNION ALL
          SELECT r.ancestor_type, r.ancestor_id, tl.to_type, tl.to_id, r.depth + 1,
                 r.visited || (tl.to_type || ':' || tl.to_id::text)
          FROM reach r
          JOIN trace_link tl ON tl.from_type = r.descendant_type AND tl.from_id = r.descendant_id
          WHERE r.depth < %d
            AND NOT (tl.to_type || ':' || tl.to_id::text = ANY(r.visited))
        )
        SELECT ancestor_type, ancestor_id, descendant_type, descendant_id, MIN(depth) AS depth
        FROM reach
        WHERE NOT (ancestor_type = descendant_type AND ancestor_id = descendant_id)
        GROUP BY ancestor_type, ancestor_id, descendant_type, descendant_id
        """.formatted(MAX_DEPTH);

    @Transactional
    public void recomputeClosure() {
        jdbc.execute("DELETE FROM trace_closure");
        jdbc.execute("""
            INSERT INTO trace_closure (ancestor_type, ancestor_id, descendant_type, descendant_id, depth)
            %s
            """.formatted(COMPUTE_CLOSURE_SQL));
    }

    /**
     * VYB-0143 AC2: reports drift without repairing it. Drift here would mean the
     * stored closure and a fresh computation disagree — which should only happen if
     * something wrote to {@code trace_link} outside this service (a migration, a
     * manual fix, direct SQL), since every link write already recomputes.
     */
    public boolean checkClosureDrift() {
        Integer diff = jdbc.queryForObject("""
            SELECT count(*) FROM (
              (SELECT ancestor_type, ancestor_id, descendant_type, descendant_id, depth FROM trace_closure
               EXCEPT (%s))
              UNION ALL
              (%s
               EXCEPT
               SELECT ancestor_type, ancestor_id, descendant_type, descendant_id, depth FROM trace_closure)
            ) AS diff
            """.formatted(COMPUTE_CLOSURE_SQL, COMPUTE_CLOSURE_SQL), Integer.class);
        return diff != null && diff > 0;
    }

    // ── Traversal (VYB-0144/0145) ────────────────────────────────────────────

    public List<TraceReachability> downstream(TraceObjectType type, UUID id, int depth) {
        return walk(type, id, depth, true);
    }

    public List<TraceReachability> upstream(TraceObjectType type, UUID id, int depth) {
        return walk(type, id, depth, false);
    }

    private List<TraceReachability> walk(TraceObjectType type, UUID id, int requestedDepth, boolean forward) {
        int depth = Math.min(Math.max(requestedDepth, 1), MAX_DEPTH); // VYB-0145 AC1 + VYB-0144 AC2
        String sql = forward
            ? """
              WITH RECURSIVE walk(node_type, node_id, depth, path) AS (
                SELECT to_type, to_id, 1, ARRAY[from_type || ':' || from_id::text, to_type || ':' || to_id::text]
                FROM trace_link WHERE from_type = ? AND from_id = ?
                UNION ALL
                SELECT tl.to_type, tl.to_id, w.depth + 1, w.path || (tl.to_type || ':' || tl.to_id::text)
                FROM walk w JOIN trace_link tl ON tl.from_type = w.node_type AND tl.from_id = w.node_id
                WHERE w.depth < ? AND NOT (tl.to_type || ':' || tl.to_id::text = ANY(w.path))
              )
              SELECT node_type, node_id, depth, path FROM walk ORDER BY depth
              """
            : """
              WITH RECURSIVE walk(node_type, node_id, depth, path) AS (
                SELECT from_type, from_id, 1, ARRAY[to_type || ':' || to_id::text, from_type || ':' || from_id::text]
                FROM trace_link WHERE to_type = ? AND to_id = ?
                UNION ALL
                SELECT tl.from_type, tl.from_id, w.depth + 1, w.path || (tl.from_type || ':' || tl.from_id::text)
                FROM walk w JOIN trace_link tl ON tl.to_type = w.node_type AND tl.to_id = w.node_id
                WHERE w.depth < ? AND NOT (tl.from_type || ':' || tl.from_id::text = ANY(w.path))
              )
              SELECT node_type, node_id, depth, path FROM walk ORDER BY depth
              """;

        return jdbc.query(sql, (ResultSet rs, int rowNum) -> mapRow(rs), type.name(), id, depth);
    }

    private TraceReachability mapRow(ResultSet rs) throws SQLException {
        Array pathArray = rs.getArray("path");
        String[] tokens = (String[]) pathArray.getArray();
        List<TraceHop> path = List.of(tokens).stream().map(TraceHop::parse).toList();
        return new TraceReachability(
            TraceObjectType.valueOf(rs.getString("node_type")),
            UUID.fromString(rs.getString("node_id")),
            rs.getInt("depth"),
            path);
    }

    // ── Coverage projection (VYB-0146) ───────────────────────────────────────

    /** AC2: callable for a whole page of requirements in one query, not one per row. */
    public Map<UUID, CoverageProjection> coverageFor(Collection<UUID> requirementIds) {
        if (requirementIds.isEmpty()) return Map.of();
        // Bound as text[] with an explicit cast rather than relying on the driver to
        // infer a uuid[] type from a Java array — that inference is unreliable across
        // pgjdbc versions for UUID specifically (String[]/Long[] fare better).
        String[] ids = requirementIds.stream().map(UUID::toString).toArray(String[]::new);
        List<CoverageProjection> rows = jdbc.query("""
            SELECT id, has_upstream, has_design, has_code, has_test
            FROM requirement_coverage WHERE id = ANY(?::uuid[])
            """,
            (rs, rowNum) -> new CoverageProjection(
                UUID.fromString(rs.getString("id")),
                rs.getBoolean("has_upstream"), rs.getBoolean("has_design"),
                rs.getBoolean("has_code"), rs.getBoolean("has_test")),
            (Object) ids);
        return rows.stream().collect(Collectors.toMap(CoverageProjection::requirementId, r -> r));
    }
}
