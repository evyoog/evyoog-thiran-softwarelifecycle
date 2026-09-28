package com.vyoog.ai;

import java.util.List;

/**
 * VYB-0667, agent 1 of 3: reads one chunk of an imported document and decides what in
 * it means anything. Everything it keeps must be quoted from the chunk; everything it
 * discards it only counts, since the point of discarding boilerplate is not to carry it
 * around in a different field.
 *
 * <p>This is the agent that does the filtering the requirement actually asks for. The
 * two agents after it never see the document — only what this one kept — so noise
 * removed here cannot reappear downstream.
 */
public interface DocumentRelevanceTriager {

    /**
     * @param discardedCount how many blocks in this chunk were judged to carry no
     *                       meaning; reported so a run can say what it ignored rather
     *                       than implying the document was all signal
     */
    record Triage(List<DocumentFinding> findings, int discardedCount, String noiseSummary) {}

    Triage triage(String chunkText, String sourceLocation);

    /**
     * Whether this agent can actually run. Extraction falls back to structural parsing
     * when it can't — a document still has to be extractable with no API key — but a
     * provider that is configured and then fails is an error the caller sees, never a
     * silent downgrade to the dumber path.
     */
    boolean available();

    String modelName();
}