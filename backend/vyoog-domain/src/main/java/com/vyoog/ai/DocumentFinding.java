package com.vyoog.ai;

import java.util.Set;

/**
 * VYB-0667: one piece of the document worth keeping — what the triage agent decided
 * carries real meaning about the system, the business, or the work, as opposed to the
 * letterhead, the confidentiality footer, or the paragraph introducing the company.
 *
 * <p>{@code evidence} is a verbatim span from the source chunk, and it is the reason
 * this record has that field at all: the orchestrator checks it actually occurs in the
 * text before the finding is allowed to survive. A model that paraphrases, embellishes
 * or invents loses the finding at that check rather than at review time.
 */
public record DocumentFinding(
    String category,
    String statement,
    String evidence,
    String sourceLocation,
    String importance) {

    /**
     * The categories the triage agent may use. A finding outside this set is dropped:
     * an open vocabulary would let the model file anything it found interesting under a
     * label it invented, which is how boilerplate gets back in.
     */
    public static final Set<String> CATEGORIES = Set.of(
        "SYSTEM_BEHAVIOUR",   // what the system does or must do
        "BUSINESS_RULE",      // a rule, policy or calculation the business imposes
        "DATA",               // entities, fields, formats, volumes, retention
        "INTEGRATION",        // another system, interface or handoff
        "CONSTRAINT",         // performance, security, regulatory, platform limits
        "PROBLEM",            // a stated defect, pain point or current-state failure
        "DEVIATION",          // where actual behaviour differs from expected
        "RISK",               // a stated risk or its consequence
        "DEPENDENCY",         // something this depends on to work
        "EXPECTATION",        // a stated business expectation or success measure
        "TERMINOLOGY");       // a domain term the document defines

    public static final Set<String> IMPORTANCE = Set.of("HIGH", "MEDIUM", "LOW");

    public boolean isWellFormed() {
        return category != null && CATEGORIES.contains(category)
            && statement != null && !statement.isBlank()
            && evidence != null && !evidence.isBlank()
            && importance != null && IMPORTANCE.contains(importance);
    }
}