package com.vyoog.design;

/**
 * VYB-0491: the five kinds a design node can be — constrained, matching the DB CHECK.
 * VYB-0816 adds {@code testing} and {@code deployment}: the two shared pipeline
 * milestones a generated flow ends in, one per flow, fed by every requirement node.
 */
public enum NodeKind { start, step, decision, integration, end, testing, deployment }
