package com.vyoog.trace;

import java.util.Optional;

/**
 * The typed endpoints a trace link can connect (VYB-0140 AC3: link types are
 * constrained — mirrors the CHECK constraint on {@code trace_link.from_type}/{@code
 * to_type} in V001__baseline.sql; changing one side without the other is a bug).
 */
public enum TraceObjectType {
    NEED,          // no backing table — an external/conceptual business need, not yet modeled
    REQUIREMENT,
    DESIGN_NODE,
    CODE,          // no backing table — inferred from commit trailers (Phase 2), not a row
    TEST,
    RELEASE,
    CLAUSE;        // backed by `clause` (V006, Phase 4) — a control clause a requirement can satisfy

    /**
     * The table to check for endpoint existence (VYB-0140 AC2), where one exists.
     * {@link Optional#empty()} means this type has no row to validate against yet —
     * see the per-constant comments above. Endpoint validation is skipped for those,
     * not silently passed as valid; {@link TraceGraphService} documents the gap.
     */
    public Optional<String> backingTable() {
        return switch (this) {
            case REQUIREMENT  -> Optional.of("requirement");
            case DESIGN_NODE  -> Optional.of("design_node");
            case TEST         -> Optional.of("test_case");
            case RELEASE      -> Optional.of("release");
            case CLAUSE       -> Optional.of("clause");
            case NEED, CODE   -> Optional.empty();
        };
    }
}
