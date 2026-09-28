package com.vyoog.requirements;

/**
 * Thrown when a caller's supplied revision no longer matches the requirement's current
 * one (VYB-0120). Carries both so the caller can show a merge/keep-mine/keep-theirs
 * choice instead of a bare "conflict" message.
 */
public class StaleRevisionException extends RuntimeException {

    private final int attemptedRevision;
    private final int currentRevision;

    public StaleRevisionException(String key, int attemptedRevision, int currentRevision) {
        super("Requirement %s was at revision %d, not %d — someone else changed it first"
            .formatted(key, currentRevision, attemptedRevision));
        this.attemptedRevision = attemptedRevision;
        this.currentRevision = currentRevision;
    }

    public int getAttemptedRevision() { return attemptedRevision; }
    public int getCurrentRevision() { return currentRevision; }
}
