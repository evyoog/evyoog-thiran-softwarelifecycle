package com.vyoog.design;

import jakarta.persistence.*;
import java.util.UUID;

/** VYB-0494: a directed edge, optionally labelled — most useful from a decision node, but never restricted to one (AC1). */
@Entity
@Table(name = "design_edge")
public class DesignEdge {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "flow_id", nullable = false)
    private UUID flowId;

    @Column(name = "from_node", nullable = false)
    private UUID fromNode;

    @Column(name = "to_node", nullable = false)
    private UUID toNode;

    private String label;

    protected DesignEdge() {}

    public DesignEdge(UUID flowId, UUID fromNode, UUID toNode, String label) {
        this.flowId = flowId;
        this.fromNode = fromNode;
        this.toNode = toNode;
        this.label = label;
    }

    public UUID getId() { return id; }
    public UUID getFlowId() { return flowId; }
    public UUID getFromNode() { return fromNode; }
    public UUID getToNode() { return toNode; }
    public String getLabel() { return label; }
}
