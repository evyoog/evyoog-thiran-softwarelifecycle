package com.vyoog.release;

/**
 * VYB-0928: PLANNED to OPEN to FROZEN to RELEASED, and FROZEN back to OPEN (a reopen, with a reason). RELEASED is
 * final. The scope of a release can change only while it is PLANNED or OPEN.
 */
public enum ReleaseState {
    PLANNED, OPEN, FROZEN, RELEASED;

    public boolean canMoveTo(ReleaseState to) {
        return switch (this) {
            case PLANNED -> to == OPEN;
            case OPEN -> to == FROZEN;
            case FROZEN -> to == RELEASED || to == OPEN;
            case RELEASED -> false;
        };
    }

    /**
     * VYB-0929: moving <em>into</em> FROZEN or RELEASED is a signature event, needing step-up authentication and
     * recording the level achieved. Opening and reopening are not.
     */
    public boolean requiresSignature() {
        return this == FROZEN || this == RELEASED;
    }

    /** Scope (what is committed to the release) can change only before it is frozen. */
    public boolean scopeEditable() {
        return this == PLANNED || this == OPEN;
    }
}
