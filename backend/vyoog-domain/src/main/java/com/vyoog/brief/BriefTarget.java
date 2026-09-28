package com.vyoog.brief;

/**
 * VYB-0451: targets that genuinely differ in content, not just the label.
 *
 * <p>Adding one is four coordinated edits, none of them optional: this enum, both
 * per-target branches in {@link BriefContentGenerator}, a forward-only migration widening
 * {@code brief.target}'s CHECK constraint, and the frontend's own TARGETS list. Miss the
 * migration and the value passes every Java layer, then dies at the insert.
 */
public enum BriefTarget { CLAUDE_CODE, CODEX, CURSOR, HUMAN }
