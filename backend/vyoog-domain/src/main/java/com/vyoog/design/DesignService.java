package com.vyoog.design;

import com.vyoog.platform.audit.AuditService;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0490–0495. {@code design_node_requirement} has no surrogate key, so it's plain
 * SQL here, same as every other join table in this codebase without one.
 */
@Service
public class DesignService {

    private final DesignFlowRepository flows;
    private final DesignNodeRepository nodes;
    private final DesignEdgeRepository edges;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public DesignService(DesignFlowRepository flows, DesignNodeRepository nodes, DesignEdgeRepository edges,
                          JdbcTemplate jdbc, AuditService audit) {
        this.flows = flows;
        this.nodes = nodes;
        this.edges = edges;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** VYB-0490 AC1: a second flow for the same application is refused, checked before the DB's own UNIQUE would refuse it anyway. */
    @Transactional
    public DesignFlow createFlow(UUID applicationId, UUID actor) {
        if (flows.findByApplicationId(applicationId).isPresent()) {
            throw new IllegalStateException("This application already has a workflow");
        }
        DesignFlow flow = flows.save(new DesignFlow(applicationId));
        audit.record(actor, "design.flow_created", "DESIGN_FLOW", flow.getId(), null,
            Map.of("applicationId", applicationId.toString()));
        return flow;
    }

    public DesignFlow flowFor(UUID applicationId) {
        return flows.findByApplicationId(applicationId).orElseThrow(NoSuchElementException::new);
    }

    public List<DesignNode> nodesIn(UUID flowId) {
        return nodes.findAllByFlowId(flowId);
    }

    public List<DesignEdge> edgesIn(UUID flowId) {
        return edges.findAllByFlowId(flowId);
    }

    public DesignNode addNode(UUID flowId, NodeKind kind, String label, String note, UUID actor) {
        flows.findById(flowId).orElseThrow(NoSuchElementException::new);
        DesignNode node = nodes.save(new DesignNode(flowId, kind, label, note));
        audit.record(actor, "design.node_added", "DESIGN_NODE", node.getId(), null,
            Map.of("flowId", flowId.toString(), "kind", kind.name(), "label", label));
        return node;
    }

    /** VYB-0494 AC1: a label is always optional, on any edge, not only ones from a decision node. */
    public DesignEdge addEdge(UUID flowId, UUID fromNode, UUID toNode, String label, UUID actor) {
        DesignNode from = nodes.findById(fromNode).orElseThrow(NoSuchElementException::new);
        DesignNode to = nodes.findById(toNode).orElseThrow(NoSuchElementException::new);
        if (!from.getFlowId().equals(flowId) || !to.getFlowId().equals(flowId)) {
            throw new IllegalArgumentException("Both nodes must belong to this flow");
        }
        DesignEdge edge = edges.save(new DesignEdge(flowId, fromNode, toNode, label));
        audit.record(actor, "design.edge_added", "DESIGN_EDGE", edge.getId(), null,
            Map.of("flowId", flowId.toString(), "from", fromNode.toString(), "to", toNode.toString()));
        return edge;
    }

    /** What a generate run did, so the caller can say it rather than guess. */
    public record Generated(int nodesCreated, int edgesCreated, int skippedAlreadyDrawn) {}

    /**
     * VYB-0666: draws the flow from the requirements the application already has, instead
     * of making somebody place a node per requirement by hand after every import.
     *
     * <p>Deterministic, not generative: one step node per approved-or-better requirement,
     * linked to that requirement, plus an edge wherever a trace link already says one
     * requirement derives from or refines another. Nothing is invented — an edge exists
     * here only because a human recorded that relationship somewhere else in the product.
     *
     * <p><strong>Additive and repeatable.</strong> A requirement that already has a node is
     * skipped rather than duplicated, and existing nodes, edges and hand-drawn structure
     * are never removed. So this can be re-run after each import and only ever adds what is
     * new — which matters because the flow is something people then edit by hand, and a
     * regenerate that wiped their edits would make the feature unusable exactly once.
     *
     * <p>DRAFT and REJECTED requirements are left out: a diagram is a picture of what the
     * application does, and neither of those has been agreed to do anything yet.
     *
     * <p>VYB-0803: REVIEWED is included alongside APPROVED and IN_REVIEW — a
     * requirement that has passed the manual review gate but not yet been approved is
     * still diagram-worthy for the same reason IN_REVIEW already was. VYB-0810: VERIFIED
     * is gone from the list — it is no longer a requirement status at all.
     */
    @Transactional
    public Generated generateFrom(UUID flowId, UUID applicationId, UUID actor) {
        flows.findById(flowId).orElseThrow(NoSuchElementException::new);

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT r.id, r.key, r.title, r.type
            FROM requirement r
            JOIN capability c ON c.id = r.capability_id
            WHERE c.application_id = ? AND r.deleted_at IS NULL
              AND r.status IN ('APPROVED', 'IN_REVIEW', 'REVIEWED')
            ORDER BY r.key
            """, applicationId);

        // Which requirements this flow already draws — the basis for being idempotent.
        Set<UUID> alreadyDrawn = new HashSet<>(jdbc.queryForList("""
            SELECT dnr.requirement_id FROM design_node_requirement dnr
            JOIN design_node dn ON dn.id = dnr.node_id
            WHERE dn.flow_id = ?
            """, UUID.class, flowId));

        Map<UUID, UUID> nodeByRequirement = new LinkedHashMap<>();
        int created = 0;
        int skipped = 0;
        for (Map<String, Object> row : rows) {
            UUID requirementId = (UUID) row.get("id");
            if (alreadyDrawn.contains(requirementId)) {
                skipped++;
                continue;
            }
            // The label is the requirement's own key and title. A generated label that
            // paraphrased the statement would be a second wording of the requirement,
            // free to drift from the one the register holds.
            String label = "%s %s".formatted(row.get("key"), row.get("title"));
            // saveAndFlush, not save: the very next line references this id as a foreign
            // key from raw JDBC, which shares the connection but not Hibernate's session —
            // an unflushed persist() here is a row that, from the JDBC insert's point of
            // view, does not exist yet.
            DesignNode node = nodes.saveAndFlush(new DesignNode(flowId, kindFor((String) row.get("type")), label, null));
            jdbc.update("INSERT INTO design_node_requirement (node_id, requirement_id) VALUES (?,?)",
                node.getId(), requirementId);
            nodeByRequirement.put(requirementId, node.getId());
            created++;
        }

        // Edges from trace links, but only between two nodes this run just created — an
        // edge onto a hand-placed node would be this method rearranging somebody's diagram.
        int edgesCreated = 0;
        if (nodeByRequirement.size() > 1) {
            List<Map<String, Object>> links = jdbc.queryForList("""
                SELECT from_id, to_id, link_type FROM trace_link
                WHERE from_type = 'REQUIREMENT' AND to_type = 'REQUIREMENT'
                  AND link_type IN ('DERIVES', 'REFINES', 'SATISFIES')
                """);
            for (Map<String, Object> link : links) {
                UUID from = nodeByRequirement.get((UUID) link.get("from_id"));
                UUID to = nodeByRequirement.get((UUID) link.get("to_id"));
                if (from == null || to == null) continue;
                edges.save(new DesignEdge(flowId, from, to, ((String) link.get("link_type")).toLowerCase()));
                edgesCreated++;
            }
        }

        // VYB-0816: two shared pipeline milestones, "Testing" and "Deployment" — one of
        // each per flow, every requirement-derived node feeding "Testing" and "Testing"
        // feeding "Deployment" in turn. Unlike the trace-link edges above, these are
        // rebuilt against the flow's *current* full node set on every run, not just what
        // this run created — a milestone summarising "how far along is this app" would be
        // stale if it only ever looked at the newest batch. Nothing here answers "is it
        // done" from requirement prose: both nodes' actual color (VYB-0816's progress
        // endpoint) reads real evidence — requirement_verification_state.is_verified
        // (the one Verified predicate, same one Release and the NoVerify detector use)
        // and presence in deployment_requirement (the one place a requirement is recorded
        // as having actually shipped) — never the requirement's own status flag.
        UUID testingNodeId = existingMilestone(flowId, NodeKind.testing);
        if (testingNodeId == null) {
            testingNodeId = nodes.saveAndFlush(new DesignNode(flowId, NodeKind.testing, "Testing", null)).getId();
            created++;
        }
        UUID deploymentNodeId = existingMilestone(flowId, NodeKind.deployment);
        if (deploymentNodeId == null) {
            deploymentNodeId = nodes.saveAndFlush(new DesignNode(flowId, NodeKind.deployment, "Deployment", null)).getId();
            created++;
        }

        List<Map<String, Object>> feeders = jdbc.queryForList("""
            SELECT DISTINCT dn.id AS node_id, dnr.requirement_id
            FROM design_node dn
            JOIN design_node_requirement dnr ON dnr.node_id = dn.id
            WHERE dn.flow_id = ? AND dn.id NOT IN (?, ?)
            """, flowId, testingNodeId, deploymentNodeId);
        for (Map<String, Object> feeder : feeders) {
            UUID nodeId = (UUID) feeder.get("node_id");
            UUID requirementId = (UUID) feeder.get("requirement_id");
            jdbc.update("INSERT INTO design_node_requirement (node_id, requirement_id) VALUES (?,?) ON CONFLICT DO NOTHING",
                testingNodeId, requirementId);
            jdbc.update("INSERT INTO design_node_requirement (node_id, requirement_id) VALUES (?,?) ON CONFLICT DO NOTHING",
                deploymentNodeId, requirementId);
            edgesCreated += jdbc.update("""
                INSERT INTO design_edge (flow_id, from_node, to_node, label) VALUES (?,?,?,NULL)
                ON CONFLICT (from_node, to_node) DO NOTHING
                """, flowId, nodeId, testingNodeId);
        }
        edgesCreated += jdbc.update("""
            INSERT INTO design_edge (flow_id, from_node, to_node, label) VALUES (?,?,?,NULL)
            ON CONFLICT (from_node, to_node) DO NOTHING
            """, flowId, testingNodeId, deploymentNodeId);

        audit.record(actor, "design.generated", "DESIGN_FLOW", flowId, null,
            Map.of("applicationId", applicationId.toString(), "nodesCreated", created,
                   "edgesCreated", edgesCreated, "skippedAlreadyDrawn", skipped));
        return new Generated(created, edgesCreated, skipped);
    }

    private UUID existingMilestone(UUID flowId, NodeKind kind) {
        List<UUID> ids = jdbc.queryForList(
            "SELECT id FROM design_node WHERE flow_id = ? AND kind = ?", UUID.class, flowId, kind.name());
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * VYB-0816: the real progress behind the "Testing" and "Deployment" milestones —
     * computed server-side for the same reason {@link #coverageSummary} is: a percentage
     * needs one denominator, decided once here rather than re-derived in the frontend.
     * Scoped to whatever requirements this flow's own nodes actually reference, not every
     * requirement the application has — a flow that hasn't drawn a requirement yet makes
     * no claim about it either way.
     */
    public record FlowProgress(int totalRequirements, int verifiedRequirements, int deployedRequirements) {}

    public FlowProgress progressFor(UUID flowId) {
        Integer total = jdbc.queryForObject("""
            SELECT count(DISTINCT dnr.requirement_id)
            FROM design_node_requirement dnr JOIN design_node dn ON dn.id = dnr.node_id
            WHERE dn.flow_id = ?
            """, Integer.class, flowId);
        Integer verified = jdbc.queryForObject("""
            SELECT count(DISTINCT dnr.requirement_id)
            FROM design_node_requirement dnr
            JOIN design_node dn ON dn.id = dnr.node_id
            JOIN requirement_verification_state vs ON vs.id = dnr.requirement_id
            WHERE dn.flow_id = ? AND vs.is_verified
            """, Integer.class, flowId);
        Integer deployed = jdbc.queryForObject("""
            SELECT count(DISTINCT dnr.requirement_id)
            FROM design_node_requirement dnr
            JOIN design_node dn ON dn.id = dnr.node_id
            WHERE dn.flow_id = ? AND EXISTS (
                SELECT 1 FROM deployment_requirement dr WHERE dr.requirement_id = dnr.requirement_id)
            """, Integer.class, flowId);
        return new FlowProgress(
            total == null ? 0 : total, verified == null ? 0 : verified, deployed == null ? 0 : deployed);
    }

    /**
     * The node shape that matches what the requirement is. Only the kinds the schema
     * allows, and only where the mapping is obvious — everything else is a plain step
     * rather than a guess dressed up as structure.
     */
    private static NodeKind kindFor(String requirementType) {
        return switch (requirementType) {
            case "INTERFACE" -> NodeKind.integration;
            case "BUSINESS_RULE" -> NodeKind.decision;
            default -> NodeKind.step;
        };
    }

    /** VYB-0495 AC1: the DB's ON DELETE CASCADE on design_edge removes this node's edges. */
    @Transactional
    public void deleteNode(UUID nodeId, UUID actor) {
        DesignNode node = nodes.findById(nodeId).orElseThrow(NoSuchElementException::new);
        nodes.delete(node);
        audit.record(actor, "design.node_deleted", "DESIGN_NODE", nodeId, null, Map.of());
    }

    /** VYB-0495 AC2: cascades remove the flow's nodes/edges/links — requirement rows themselves are never touched. */
    @Transactional
    public void deleteFlow(UUID flowId, UUID actor) {
        DesignFlow flow = flows.findById(flowId).orElseThrow(NoSuchElementException::new);
        flows.delete(flow);
        audit.record(actor, "design.flow_deleted", "DESIGN_FLOW", flowId, null, Map.of());
    }

    /** VYB-0492 AC1: a node may implement several requirements — repeat calls just add more rows. */
    @Transactional
    public void linkRequirement(UUID nodeId, UUID requirementId, UUID actor) {
        Boolean exists = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM design_node_requirement WHERE node_id = ? AND requirement_id = ?)",
            Boolean.class, nodeId, requirementId);
        if (!Boolean.TRUE.equals(exists)) {
            jdbc.update("INSERT INTO design_node_requirement (node_id, requirement_id) VALUES (?,?)", nodeId, requirementId);
            audit.record(actor, "design.requirement_linked", "DESIGN_NODE", nodeId, null,
                Map.of("requirementId", requirementId.toString()));
        }
    }

    @Transactional
    public void unlinkRequirement(UUID nodeId, UUID requirementId, UUID actor) {
        jdbc.update("DELETE FROM design_node_requirement WHERE node_id = ? AND requirement_id = ?", nodeId, requirementId);
        audit.record(actor, "design.requirement_unlinked", "DESIGN_NODE", nodeId, null,
            Map.of("requirementId", requirementId.toString()));
    }

    public List<UUID> requirementsFor(UUID nodeId) {
        return jdbc.queryForList("SELECT requirement_id FROM design_node_requirement WHERE node_id = ?", UUID.class, nodeId);
    }

    public record UncoveredRequirement(String id, String key, String title) {}

    /** VYB-0493 AC1: requirements with no design node — the same signal the nodesign detector already reads. */
    public List<UncoveredRequirement> requirementsWithNoDesign(UUID applicationId) {
        return jdbc.query("""
            SELECT r.id, r.key, r.title FROM requirement r
            JOIN capability c ON c.id = r.capability_id
            JOIN requirement_coverage rc ON rc.id = r.id
            WHERE c.application_id = ? AND r.deleted_at IS NULL AND NOT rc.has_design
            ORDER BY r.key
            """,
            (rs, n) -> new UncoveredRequirement(rs.getString("id"), rs.getString("key"), rs.getString("title")),
            applicationId);
    }

    public record OrphanNode(String id, String label, String kind) {}

    /** VYB-0493 AC2: "design nobody asked for" — a node with zero requirements behind it. */
    public List<OrphanNode> nodesWithNoRequirement(UUID flowId) {
        return jdbc.query("""
            SELECT n.id, n.label, n.kind FROM design_node n
            WHERE n.flow_id = ? AND NOT EXISTS (
              SELECT 1 FROM design_node_requirement dnr WHERE dnr.node_id = n.id)
            ORDER BY n.label
            """,
            (rs, n) -> new OrphanNode(rs.getString("id"), rs.getString("label"), rs.getString("kind")),
            flowId);
    }

    /**
     * VYB-0537 (session 16): a real coverage badge needs a denominator, and neither
     * {@link #requirementsWithNoDesign} nor {@link #nodesWithNoRequirement} returns
     * one — both are "the offending set," not "offending / total." Two real
     * percentages, computed server-side rather than trusting the frontend to fetch
     * both a numerator list and a separate total and divide correctly: how many of
     * this application's requirements have at least one design node, and how many of
     * this flow's nodes have at least one requirement.
     */
    public record CoverageSummary(
        int totalRequirements, int requirementsWithDesign,
        int totalNodes, int nodesWithRequirement) {}

    public CoverageSummary coverageSummary(UUID applicationId, UUID flowId) {
        Integer totalReq = jdbc.queryForObject("""
            SELECT count(*) FROM requirement r JOIN capability c ON c.id = r.capability_id
            WHERE c.application_id = ? AND r.deleted_at IS NULL
            """, Integer.class, applicationId);
        Integer coveredReq = jdbc.queryForObject("""
            SELECT count(*) FROM requirement r JOIN capability c ON c.id = r.capability_id
            JOIN requirement_coverage rc ON rc.id = r.id
            WHERE c.application_id = ? AND r.deleted_at IS NULL AND rc.has_design
            """, Integer.class, applicationId);
        Integer totalNodes = jdbc.queryForObject(
            "SELECT count(*) FROM design_node WHERE flow_id = ?", Integer.class, flowId);
        Integer coveredNodes = jdbc.queryForObject("""
            SELECT count(*) FROM design_node n
            WHERE n.flow_id = ? AND EXISTS (SELECT 1 FROM design_node_requirement dnr WHERE dnr.node_id = n.id)
            """, Integer.class, flowId);
        return new CoverageSummary(
            totalReq == null ? 0 : totalReq, coveredReq == null ? 0 : coveredReq,
            totalNodes == null ? 0 : totalNodes, coveredNodes == null ? 0 : coveredNodes);
    }
}
