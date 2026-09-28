package com.vyoog.ai;

import java.util.List;

/**
 * VYB-0824: proposes test cases for a requirement — grounded, individual/validation
 * cases for the requirement's own statement and acceptance criteria, and, only when
 * trace links exist, dependency cases validating it together with what it depends on
 * and what depends on it (the same "depends on" relationships {@code DesignService}
 * draws its diagram edges from). One call, one categorized reply — deliberately a
 * single agent, not a pipeline like {@link RequirementBriefAnalyst}'s document-analysis
 * siblings. Mirrors {@link RequirementElaborationAdvisor}'s shape: stateless,
 * grounded-input-only records, nothing persisted here. "AI proposes, the human
 * decides": a returned {@link Suggestion} is never itself a test case — only
 * {@code TestCaseService.draft}, called after a person accepts one, creates one.
 * VYB-0829: this platform has no source-code access anywhere (no diff, no file
 * content, no repo connection — {@code TraceObjectType.CODE} deliberately has no
 * backing table) — the only real signal that a requirement is already implemented is
 * whether any commit's {@code Requirement: KEY} trailer links to it, and the only
 * content available from that commit is its message. {@link RequirementInput#linkedCommitMessages()}
 * carries exactly that: empty for a requirement nothing has been committed against yet.
 */
public interface TestCaseGenerator {

    enum Category { INDIVIDUAL, DEPENDENCY }

    /** One proposed test case a person can accept as-is, edit first, or discard. */
    record Suggestion(Category category, String title, String description, String rationale) {
        public boolean isWellFormed() {
            return category != null && title != null && !title.isBlank();
        }
    }

    /**
     * Grounded input only, never the entity itself — same convention as {@link RequirementElaborationAdvisor.Input}.
     *
     * @param linkedCommitMessages the message of every commit whose {@code Requirement:}
     *     trailer links it to this requirement (VYB-0316) — the only source-adjacent
     *     content this platform has, since no diff/file content is ever stored. Empty
     *     means nothing has been committed against this requirement yet.
     */
    record RequirementInput(String key, String title, String statement, List<String> acceptanceCriteria,
                             List<String> linkedCommitMessages) {}

    /** {@code direction} is "UPSTREAM" (this requirement depends on it) or "DOWNSTREAM" (it depends on this requirement). */
    record RelatedRequirement(String key, String title, String statement, String direction) {}

    /**
     * @param related every requirement directly trace-linked to {@code requirement},
     *     both directions — empty when it has none, in which case the reply must be
     *     INDIVIDUAL-only.
     * @throws AiProviderUnavailableException if the provider is unconfigured or fails
     */
    List<Suggestion> generate(RequirementInput requirement, List<RelatedRequirement> related);

    /** False means the caller must refuse explicitly, never silently return an empty list. */
    boolean available();

    String modelName();
}
