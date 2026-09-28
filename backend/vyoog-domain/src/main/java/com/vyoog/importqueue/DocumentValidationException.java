package com.vyoog.importqueue;

/** VYB-0631 AC1: a STANDARD_SPEC (or REQIF/EXCEL) document that fails validation names exactly which rule. */
public class DocumentValidationException extends RuntimeException {

    private final String failedRule;

    public DocumentValidationException(String failedRule, String message) {
        super(message);
        this.failedRule = failedRule;
    }

    public String getFailedRule() { return failedRule; }
}
