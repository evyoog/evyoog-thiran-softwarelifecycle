package com.vyoog.defect;

/** VYB-0321: mandatory before a defect can close — see {@link Defect#close}. */
public enum RootCause {
    REQUIREMENT_AMBIGUITY, REQUIREMENT_OMISSION, CODING_ERROR, ENVIRONMENT, DATA, UNKNOWN;

    /** VYB-0323: the split that proves requirement quality affects outcomes. */
    public boolean isRequirementCaused() {
        return this == REQUIREMENT_AMBIGUITY || this == REQUIREMENT_OMISSION;
    }
}
