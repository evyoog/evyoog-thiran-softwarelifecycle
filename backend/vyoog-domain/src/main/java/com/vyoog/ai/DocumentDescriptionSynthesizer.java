package com.vyoog.ai;

import java.util.List;

/**
 * VYB-0667, agent 2 of 3: turns the surviving findings into the description a reader
 * actually wants — what the document is about, what system or process it describes,
 * what it expects, what constrains it, and what it says is wrong today.
 *
 * <p>It is given the findings, never the document. That is the whole reason the
 * pipeline has three agents rather than one long prompt: a synthesiser that can still
 * see the letterhead will eventually mention the letterhead.
 */
public interface DocumentDescriptionSynthesizer {

    /**
     * @param revisionGuidance claims the critic could not tie to any finding on the
     *                         previous attempt; empty on the first pass
     */
    record Synthesis(String description, List<String> themes) {}

    Synthesis synthesize(String filename, List<DocumentFinding> findings, List<String> revisionGuidance);

    String modelName();
}
