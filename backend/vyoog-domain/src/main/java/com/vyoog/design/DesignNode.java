package com.vyoog.design;

import jakarta.persistence.*;
import java.util.UUID;

/** VYB-0491: a step in the flow. {@code kind} names its shape (VYB-0533 on the frontend); {@code integration} is external by construction, not by a separate flag (VYB-0491 AC2). */
@Entity
@Table(name = "design_node")
public class DesignNode {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "flow_id", nullable = false)
    private UUID flowId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NodeKind kind;

    @Column(nullable = false)
    private String label;

    private String note;

    protected DesignNode() {}

    public DesignNode(UUID flowId, NodeKind kind, String label, String note) {
        this.flowId = flowId;
        this.kind = kind;
        this.label = label;
        this.note = note;
    }

    public UUID getId() { return id; }
    public UUID getFlowId() { return flowId; }
    public NodeKind getKind() { return kind; }
    public String getLabel() { return label; }
    public String getNote() { return note; }
}
