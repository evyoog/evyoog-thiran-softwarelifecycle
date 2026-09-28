package com.vyoog.ai;

import java.util.List;
import java.util.Set;

/**
 * Agent 4 of 4: turns a surviving {@link DocumentFinding} into something a person can
 * actually make a build decision from.
 *
 * <p>The three agents before this one answer "what is in this document". None of them
 * answer "what would it mean to build this", which is the question a reviewer working
 * through an import queue is actually holding. Triage deliberately produces a short
 * reading of one quote — that is the right output for a relevance filter and the wrong
 * output for a requirement card, which is why candidates built straight from triage
 * read as restatements of the sentence above them.
 *
 * <p>Two things this must never produce, and the prompt says so explicitly:
 *
 * <ul>
 *   <li><b>No effort, duration, cost, story points or team size.</b> Principle 7 — the
 *       delivery tool and finance own those, and a model guessing at them here would be
 *       inventing the one kind of number this platform is not allowed to hold. A
 *       readiness verdict is about what is <em>known</em>, not what it would take.
 *   <li><b>No facts the document does not contain.</b> {@code dependsOn} may only name
 *       what the document names. {@code openQuestions} is the honest inverse: the things
 *       it fails to say, which is exactly what makes a requirement unbuildable.
 * </ul>
 *
 * <p>Findings are analysed in batches rather than one call each, so the analyst sees
 * several findings together and can relate them — the triage agent never can, since it
 * only ever sees one chunk. It also keeps the call count proportional to the document
 * instead of to the finding count.
 */
public interface RequirementBriefAnalyst {

    /**
     * @param index position of the finding this brief is for, within the list handed to
     *     {@link #analyse}. The model is asked to echo it back rather than to return
     *     briefs positionally, so a short or reordered reply misaligns nothing — a brief
     *     whose index doesn't resolve is dropped instead of being attached to the wrong
     *     finding.
     * @param title a short naming phrase for the register's Title column. Without this
     *     an AI-extracted requirement commits with its synthetic tag ("f1", "f2") as its
     *     title, which is what a register full of unreadable rows looks like.
     * @param statement an authored, testable requirement statement grounded in the
     *     finding's evidence — what the candidate's editable text becomes
     * @param type one of {@link Brief#TYPES}, or null when the finding gives no basis
     * @param priority one of {@link Brief#PRIORITIES}, or null when the document says
     *     nothing about how much this matters — the register's default is a better
     *     answer than a model filling the field to avoid leaving it empty
     * @param acceptanceCriteria testable conditions drawn from the evidence, each one
     *     checkable against a built system. Empty when the document states an intent
     *     without any condition that could be checked.
     * @param description what this actually asks for, in terms of the product
     * @param entails what building it involves: the behaviour, data and interfaces it
     *     touches. Never how long or how much.
     * @param dependsOn only what the document itself names as a dependency
     * @param openQuestions what the document does not answer and someone must, before
     *     this can be built
     * @param readiness one of {@link #READINESS}
     */
    record Brief(
        int index,
        String title,
        String statement,
        String type,
        String priority,
        List<String> acceptanceCriteria,
        String description,
        List<String> entails,
        List<String> dependsOn,
        List<String> openQuestions,
        String readiness) {

        /**
         * Closed vocabulary, checked in code, for the same reason {@link
         * DocumentFinding#CATEGORIES} is: an open one lets the model file anything under
         * a label it invented. These describe how completely the document specifies the
         * requirement — deliberately not how hard it is, which this platform does not
         * measure.
         */
        public static final Set<String> READINESS = Set.of(
            "CLEAR",                // the document says enough to build against
            "NEEDS_CLARIFICATION",  // buildable in outline, but named questions block it
            "UNDERSPECIFIED");      // the document gestures at it without saying what it is

        /** The eight the register accepts — the only values {@code requirement.type} may hold. */
        public static final Set<String> TYPES = Set.of(
            "FUNCTIONAL", "NON_FUNCTIONAL", "BUSINESS_RULE", "INTERFACE", "DATA", "REPORT", "SECURITY", "COMPLIANCE");

        public static final Set<String> PRIORITIES = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

        /**
         * Only the fields with no honest fallback are required here. {@code type} and
         * {@code priority} are optional by design — a document that gives no basis for a
         * priority should yield none, and the register's own default is a better answer
         * than a model picking one to fill the field. {@code title} is optional for a
         * different reason: commit already falls back to the candidate's tag without one,
         * and failing the whole brief over a missing title would throw away the statement,
         * the criteria and the open questions with it — the fields that cost the most to
         * lose. All three are read through the {@code valid*} accessors, which return null
         * rather than failing, so one unusable field never costs the rest of the brief.
         */
        public boolean isWellFormed() {
            return index >= 0
                && statement != null && !statement.isBlank()
                && description != null && !description.isBlank()
                && readiness != null && READINESS.contains(readiness);
        }

        /** @return the title if the model gave one, else null so commit falls back to the tag. */
        public String validTitle() {
            return title != null && !title.isBlank() ? title.strip() : null;
        }

        /** @return the type if it is one the register accepts, else null — never a guess. */
        public String validType() {
            return type != null && TYPES.contains(type) ? type : null;
        }

        /** @return the priority if the document supported one, else null so the register's default stands. */
        public String validPriority() {
            return priority != null && PRIORITIES.contains(priority) ? priority : null;
        }

        /** Rebases a model-returned, batch-relative index onto the full finding list. */
        public Brief at(int absoluteIndex) {
            return new Brief(absoluteIndex, title, statement, type, priority, acceptanceCriteria,
                description, entails, dependsOn, openQuestions, readiness);
        }
    }

    /**
     * @param documentDescription what the synthesiser concluded the document is about,
     *     passed as context so a brief reads the finding in the document's terms rather
     *     than in isolation
     * @param findings one batch, in order; a brief's {@code index} is relative to this list
     */
    List<Brief> analyse(String filename, String documentDescription, List<DocumentFinding> findings);

    /** False means candidates keep the triage statement and say so, rather than silently looking unanalysed. */
    boolean available();

    String modelName();
}
