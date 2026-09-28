package com.vyoog.identity;

/** VYB-0303 AC1: refused with the specific level that was needed, not a bare 401. */
public class StepUpRequiredException extends RuntimeException {

    private final String requiredLevel;

    public StepUpRequiredException(String requiredLevel) {
        super("This action requires the '%s' authentication level".formatted(requiredLevel));
        this.requiredLevel = requiredLevel;
    }

    public String getRequiredLevel() { return requiredLevel; }
}
