package com.vyoog.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0215: a visual need→requirement→design→code→test graph. {@link TraceGraphService}
 * already has everything this needs — bounded upstream/downstream traversal
 * ({@link TraceGraphService#upstream}/{@link TraceGraphService#downstream}) and typed
 * nodes/links — but returns one root-to-node path per reachable node
 * ({@link TraceReachability}), which is right for "how is X connected to Y" and wrong
 * for "draw the graph": the same node reachable by two paths would appear twice, and
 * there's no single deduplicated node+edge set to hand a renderer.
 *
 * <p>This flattens both walks into exactly that: one node per distinct
 * {@code (type, id)}, one edge per distinct hop, human-readable labels resolved from
 * whichever table actually backs each type ({@code NEED}/{@code CODE} have none — see
 * {@link TraceObjectType} — and get a plain "no record" label rather than a fabricated
 * one). It also folds in {@code design_node_requirement} rows as {@code COVERS} edges:
 * that relationship is real (VYB-0147's coverage matrix already reads it) but was never
 * modeled as a {@code trace_link} (see {@code TraceObjectType.DESIGN_NODE}'s own note)
 * — a design-aware graph would be visibly incomplete without it.
 */
@Service
public class TraceGraphAssemblyService {

    private final TraceGraphService trace;
    private final JdbcTemplate jdbc;

    public TraceGraphAssemblyService(TraceGraphService trace, JdbcTemplate jdbc) {
        this.trace = trace;
        this.jdbc = jdbc;
    }

    public record GraphNode(String type, String id, String label, boolean root) {}
    public record GraphEdge(String fromType, String fromId, String toType, String toId, String linkType) {}
    public record Graph(List<GraphNode> nodes, List<GraphEdge> edges) {}

    public Graph graphFor(TraceObjectType rootType, UUID rootId, int depth) {
        Set<String> nodeKeys = new LinkedHashSet<>();
        Map<String, TraceObjectType> nodeTypes = new LinkedHashMap<>();
        Map<String, UUID> nodeIds = new LinkedHashMap<>();
        List<GraphEdge> edges = new ArrayList<>();
        Set<String> edgeKeys = new LinkedHashSet<>(); // dedupe: two paths can share a hop

        addNode(nodeKeys, nodeTypes, nodeIds, rootType, rootId);

        for (TraceReachability r : trace.downstream(rootType, rootId, depth)) {
            walkPath(r.path(), rootType, rootId, nodeKeys, nodeTypes, nodeIds, edges, edgeKeys, true);
        }
        for (TraceReachability r : trace.upstream(rootType, rootId, depth)) {
            walkPath(r.path(), rootType, rootId, nodeKeys, nodeTypes, nodeIds, edges, edgeKeys, false);
        }

        // design_node_requirement: real coverage, never stored as a trace_link (see class doc).
        if (rootType == TraceObjectType.REQUIREMENT) {
            for (UUID designNodeId : jdbc.queryForList(
                    "SELECT node_id FROM design_node_requirement WHERE requirement_id = ?", UUID.class, rootId)) {
                addNode(nodeKeys, nodeTypes, nodeIds, TraceObjectType.DESIGN_NODE, designNodeId);
                addEdge(edges, edgeKeys, TraceObjectType.DESIGN_NODE, designNodeId, rootType, rootId, "COVERS");
            }
        } else if (rootType == TraceObjectType.DESIGN_NODE) {
            for (UUID reqId : jdbc.queryForList(
                    "SELECT requirement_id FROM design_node_requirement WHERE node_id = ?", UUID.class, rootId)) {
                addNode(nodeKeys, nodeTypes, nodeIds, TraceObjectType.REQUIREMENT, reqId);
                addEdge(edges, edgeKeys, rootType, rootId, TraceObjectType.REQUIREMENT, reqId, "COVERS");
            }
        }

        List<GraphNode> nodes = nodeKeys.stream()
            .map(k -> {
                TraceObjectType t = nodeTypes.get(k);
                UUID id = nodeIds.get(k);
                return new GraphNode(t.name(), id.toString(), label(t, id), t == rootType && id.equals(rootId));
            })
            .toList();
        return new Graph(nodes, edges);
    }

    private void walkPath(List<TraceHop> path, TraceObjectType rootType, UUID rootId,
                           Set<String> nodeKeys, Map<String, TraceObjectType> nodeTypes, Map<String, UUID> nodeIds,
                           List<GraphEdge> edges, Set<String> edgeKeys, boolean forward) {
        // Each path already starts at the root and ends at the reached node (see
        // TraceGraphService.walk's seed row) — hop i -> i+1 is one real edge each.
        for (int i = 0; i + 1 < path.size(); i++) {
            TraceHop a = path.get(i);
            TraceHop b = path.get(i + 1);
            addNode(nodeKeys, nodeTypes, nodeIds, a.type(), a.id());
            addNode(nodeKeys, nodeTypes, nodeIds, b.type(), b.id());
            if (forward) addEdge(edges, edgeKeys, a.type(), a.id(), b.type(), b.id(), null);
            else addEdge(edges, edgeKeys, b.type(), b.id(), a.type(), a.id(), null);
        }
    }

    private void addNode(Set<String> nodeKeys, Map<String, TraceObjectType> nodeTypes, Map<String, UUID> nodeIds,
                          TraceObjectType type, UUID id) {
        String key = type.name() + ":" + id;
        if (nodeKeys.add(key)) {
            nodeTypes.put(key, type);
            nodeIds.put(key, id);
        }
    }

    private void addEdge(List<GraphEdge> edges, Set<String> edgeKeys,
                          TraceObjectType fromType, UUID fromId, TraceObjectType toType, UUID toId, String linkType) {
        // The real link type (if this exact hop is a real trace_link) is resolved once,
        // read-only — this is a display graph, not a second source of truth for it.
        String resolved = linkType != null ? linkType : jdbc.query(
            "SELECT link_type FROM trace_link WHERE from_type = ? AND from_id = ? AND to_type = ? AND to_id = ? LIMIT 1",
            (rs, n) -> rs.getString(1), fromType.name(), fromId, toType.name(), toId)
            .stream().findFirst().orElse("LINKED");
        String key = fromType + ":" + fromId + ">" + toType + ":" + toId + ":" + resolved;
        if (edgeKeys.add(key)) {
            edges.add(new GraphEdge(fromType.name(), fromId.toString(), toType.name(), toId.toString(), resolved));
        }
    }

    private String label(TraceObjectType type, UUID id) {
        return switch (type) {
            case REQUIREMENT -> jdbc.query(
                "SELECT key, title FROM requirement WHERE id = ?",
                (rs, n) -> rs.getString("key") + " — " + rs.getString("title"), id)
                .stream().findFirst().orElse("(deleted requirement)");
            case DESIGN_NODE -> jdbc.query(
                "SELECT kind, label FROM design_node WHERE id = ?",
                (rs, n) -> rs.getString("kind") + ": " + rs.getString("label"), id)
                .stream().findFirst().orElse("(deleted design node)");
            case TEST -> jdbc.query(
                "SELECT key, title FROM test_case WHERE id = ?",
                (rs, n) -> rs.getString("key") + " — " + rs.getString("title"), id)
                .stream().findFirst().orElse("(deleted test case)");
            case RELEASE -> jdbc.query(
                "SELECT name FROM release WHERE id = ?", (rs, n) -> rs.getString("name"), id)
                .stream().findFirst().orElse("(deleted release)");
            case CLAUSE -> jdbc.query(
                "SELECT standard, section FROM clause WHERE id = ?",
                (rs, n) -> rs.getString("standard") + (rs.getString("section") != null ? " " + rs.getString("section") : ""), id)
                .stream().findFirst().orElse("(deleted clause)");
            // NEED/CODE genuinely have no backing table (TraceObjectType.backingTable() is
            // empty for both) — labeling them with anything but this would be inventing data.
            case NEED -> "Need " + id + " (no record — conceptual only)";
            case CODE -> "Commit " + id + " (no record — inferred from commit trailers)";
        };
    }
}
