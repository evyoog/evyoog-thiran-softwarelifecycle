package com.vyoog.ai;

import java.util.List;

/**
 * VYB-0817: an explicitly-requested, developer-facing expansion of an already-approved
 * requirement for an implementation brief — not a second opinion on what the
 * requirement should say (that is {@link RequirementRewriteAdvisor}, which produces a
 * replacement statement) and not an extraction from a document (that is {@link
 * RequirementBriefAnalyst}, which turns unreviewed evidence into a candidate). The
 * requirement here has already been authored and approved; this only elaborates on it
 * in more detail for whoever builds it, alongside — never in place of — the statement
 * a human wrote and approved.
 */
public interface RequirementElaborationAdvisor {

    /**
     * @param index position of the requirement this elaboration is for, within the list
     *     handed to {@link #elaborate}. Echoed back rather than trusted positionally, so
     *     a short or reordered reply misaligns nothing — see {@link RequirementBriefAnalyst.Brief#index}
     *     for the same convention.
     * @param detail 3-6 sentences of plain, detailed prose expanding what the
     *     requirement already says — its trigger, the system's expected behaviour, and
     *     what "done" looks like — grounded only in the statement and acceptance
     *     criteria given. Never a fact, threshold, system or number the input did not
     *     already contain.
     */
    record Elaboration(int index, String detail) {
        public boolean isWellFormed() {
            return index >= 0 && detail != null && !detail.isBlank();
        }
    }

    /** What one requirement looks like to this advisor — grounded input only, never the entity itself. */
    record Input(String key, String title, String statement, List<String> acceptanceCriteria) {}

    /**
     * @param requirements one batch, in order; an elaboration's {@code index} is
     *     relative to this list
     * @throws AiProviderUnavailableException if the provider is unconfigured or fails
     */
    List<Elaboration> elaborate(String applicationName, List<Input> requirements);

    /** False means the caller must say elaboration was requested but unavailable, never silently omit it. */
    boolean available();

    String modelName();
}
