package com.vyoog.trace;

import java.util.UUID;

/**
 * VYB-0146: whether a requirement has upstream, design, code and test links. Derived
 * from the {@code requirement_coverage} SQL view (V001__baseline.sql) on every read —
 * never stored, per the same "predicate, not a flag" principle as verification state.
 */
public record CoverageProjection(UUID requirementId, boolean hasUpstream, boolean hasDesign,
                                  boolean hasCode, boolean hasTest) {}
