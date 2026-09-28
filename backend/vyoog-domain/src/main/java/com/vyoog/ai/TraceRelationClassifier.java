package com.vyoog.ai;

import java.util.List;

/**
 * VYB-0630 AI enrichment (2026-08-12): given an import candidate's statement and a
 * shortlist of existing requirements {@link com.vyoog.ai.SimilaritySearchService}
 * already found nearby by embedding distance, decides which (if any) the candidate
 * should trace to, and how. Nearby-by-embedding is not the same as related-by-meaning
 * — this exists specifically to tell "similar wording, unrelated concern" apart from
 * a real {@code SATISFIES}/{@code DERIVES}/{@code REFINES} relationship, the same
 * "propose, never auto-apply" shape every advisory path in this package uses.
 */
public interface TraceRelationClassifier {

    String modelName();

    record Candidate(String key, String statement) {}

    /** {@code linkType} is one of {@link com.vyoog.trace.TraceLinkType}'s names. */
    record ProposedLink(String key, String linkType, String rationale) {}

    /**
     * @return only the candidates judged genuinely related — silence on the rest,
     *     not a "NONE" entry for every one that didn't qualify.
     * @throws AiProviderUnavailableException the provider couldn't be reached or
     *     isn't configured.
     */
    List<ProposedLink> classify(String statement, List<Candidate> nearby);
}
