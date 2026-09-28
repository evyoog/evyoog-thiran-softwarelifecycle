package com.vyoog.trace;

/** Mirrors the CHECK constraint on {@code trace_link.link_type} in V001__baseline.sql. */
public enum TraceLinkType {
    SATISFIES, DERIVES, VERIFIES, IMPLEMENTS, REFINES, CONFLICTS
}
