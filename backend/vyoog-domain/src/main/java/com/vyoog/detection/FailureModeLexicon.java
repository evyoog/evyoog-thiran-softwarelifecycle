package com.vyoog.detection;

import java.util.List;
import java.util.regex.Pattern;

/**
 * VYB-0613 AC1: the rule-based fallback for "happy path only" — and, in this
 * environment, the only implementation there is, since no classifier is configured
 * (see BUILD-REGISTER.md). A statement or its acceptance criteria mentioning none of
 * these terms is silent on what happens when the normal path fails.
 */
public final class FailureModeLexicon {

    private static final List<String> TERMS = List.of(
        "fail", "failure", "error", "invalid", "reject", "denied", "unauthorized",
        "unavailable", "timeout", "time out", "retry", "unable to", "cannot",
        "exceeds", "exceeded", "out of range", "not found", "conflict", "duplicate",
        "rollback", "roll back", "revert", "expire", "expired", "unreachable",
        "throttle", "rate limit", "malformed", "corrupt");

    private static final Pattern PATTERN = Pattern.compile(
        "\\b(" + String.join("|", TERMS.stream().map(Pattern::quote).toList()) + ")\\b",
        Pattern.CASE_INSENSITIVE);

    private FailureModeLexicon() {}

    /** True if any failure-mode language appears anywhere in the given texts. */
    public static boolean mentionsFailureHandling(List<String> texts) {
        return texts.stream().filter(java.util.Objects::nonNull).anyMatch(t -> PATTERN.matcher(t).find());
    }

    public static int termCount() {
        return TERMS.size();
    }
}
