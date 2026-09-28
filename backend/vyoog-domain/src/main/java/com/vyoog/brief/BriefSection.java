package com.vyoog.brief;

import java.util.Set;

/**
 * The optional parts of a brief, so the person generating one can leave out what this
 * particular handoff doesn't need.
 *
 * <p>Only sections the generator can actually produce from data Vyoog holds are listed.
 * A toggle for a section nothing can fill would render a brief that silently omits it
 * and a checkbox that silently does nothing — the header, the scope line and the
 * requirement bodies are therefore not here at all: a brief without them is not a brief,
 * so they are never optional.
 */
public enum BriefSection {

    /** §0 — what the agent is being asked to do, rewritten per target. */
    CONTEXT,

    /** §1 — counts per category and the test-coverage line. */
    CATEGORY_REVIEW,

    /** The acceptance-criteria bullets under each requirement in §2. */
    ACCEPTANCE_CRITERIA,

    /**
     * The requirements carrying no acceptance criteria, gathered into their own section.
     * Not invented: it is exactly the set §2 already marks "No acceptance criteria
     * recorded", collected so the agent is told to ask rather than guess.
     */
    OPEN_QUESTIONS,

    /** Vyoog's own quality score per requirement, so the agent knows which parts rest on firmer ground. */
    QUALITY_APPENDIX,

    /** §3 — the definition of done. */
    DEFINITION_OF_DONE,

    /**
     * The commit-trailer line inside the definition of done. Independently toggleable
     * because a team that doesn't use trailers still wants the rest of §3, and because
     * the HUMAN target drops it regardless — a target choice this must not override.
     */
    COMMIT_TRAILER;

    /** What a caller that says nothing gets: everything, exactly as briefs read before sections existed. */
    public static final Set<BriefSection> ALL = Set.of(values());
}
