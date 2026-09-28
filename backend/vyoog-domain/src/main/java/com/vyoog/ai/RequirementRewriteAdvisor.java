package com.vyoog.ai;

import java.util.List;
import java.util.Map;

/**
 * VYB-0794 (Part 2): a concrete rewrite alongside {@code QualityScoreService}'s
 * existing deterministic score/breakdown — that service stays exactly as it is (a
 * pure function, safe on every keystroke); this is the separate, explicitly
 * user-triggered step for someone who wants to see what a better version of their
 * own statement could actually read like, not just which category lost points.
 */
public interface RequirementRewriteAdvisor {

    /** VYB-0666-style convention: recorded alongside the suggestion — which model actually produced it. */
    String modelName();

    /**
     * @param rewrittenStatement a complete replacement for the original statement —
     *     never a diff/patch, so it's directly pasteable.
     * @param changes one short sentence per concrete thing the rewrite fixed, tied
     *     back to what {@code QualityScoreService}'s breakdown penalized (ambiguous
     *     wording, missing acceptance criteria, no traceability, too short) — not
     *     generic writing advice unrelated to this tool's own scoring.
     */
    record Suggestion(String rewrittenStatement, List<String> changes) {}

    /**
     * @param qualityBreakdown the same breakdown {@code QualityScoreService.score()}
     *     already computed for this exact statement — passed in rather than
     *     recomputed, so the model is told exactly what this tool already knows is
     *     wrong instead of re-deriving it less reliably itself.
     * @throws AiProviderUnavailableException reused here for "the provider couldn't be
     *     reached or isn't configured" — the same reason a classification, embedding,
     *     or adjudication call would fail.
     */
    Suggestion suggest(String statement, Map<String, Integer> qualityBreakdown);
}
