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

    /** Scope (what is committed to the release) can change only before it is frozen. */
    public boolean scopeEditable() {
        return this == PLANNED || this == OPEN;
    }
}
