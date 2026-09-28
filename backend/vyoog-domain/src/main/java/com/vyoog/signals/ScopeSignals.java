package com.vyoog.signals;

import java.time.Instant;
import java.util.Map;

/**
 * VYB-0460–0463: eight exact figures, never combined into an effort or duration
 * estimate — there is deliberately no ninth field for that, and no endpoint anywhere
 * in this codebase returns one (VYB-0463 AC1/AC2). {@code queries} carries the literal
 * SQL template behind each figure (VYB-0461 AC1) so the interface can show its work;
 * the templates use {@code ?} placeholders, never the caller's actual capability ids,
 * so displaying them is not displaying a live, re-executable query with this scope's
 * real values baked in.
 */
public record ScopeSignals(
    long requirementCount,
    long acceptanceCriteriaCount,
    long dependencyDepth,
    long crossApplicationReach,
    long ambiguityLoad,
    long openGaps,
    double changeRate,
    double novelty,
    Instant computedAt,
    Map<String, String> queries) {
}
