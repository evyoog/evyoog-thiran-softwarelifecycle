package com.vyoog.ai;

/**
 * VYB-0612: judges whether two requirements actually contradict — never called
 * automatically to produce a finding without going through here first (AC1).
 *
 * <p>No implementation of this interface is registered as a Spring bean in this
 * environment — no LLM API key or endpoint is configured (see BUILD-REGISTER.md).
 * {@code ConflictingRequirementsDetector} autowires this as {@code Optional
 * <LlmAdjudicator>} and reports itself unavailable (VYB-0603) rather than ever
 * fabricating an adjudication. Wiring a real provider later is one new
 * {@code @Component} implementing this interface — nothing about the detector or the
 * finding pipeline changes.
 */
public interface LlmAdjudicator {

    /** VYB-0612 AC3: recorded on every resulting finding — see {@link Candidate#model}. */
    String modelAndPromptVersion();

    /**
     * VYB-0911: whether this adjudicator can run at all. False when AI is switched off or has no
     * key, which is a configuration and not a failure: callers should skip quietly (and spend no
     * AI-call budget) rather than call {@link #adjudicate} and catch the refusal. True by default,
     * so an implementation that is always usable need not say so.
     */
    default boolean isConfigured() {
        return true;
    }

    record Verdict(boolean contradicts, String explanation, double confidence) {}

    /**
     * @throws AiProviderUnavailableException reused here for "the provider couldn't
     *     be reached" — an LLM call failing for the same reasons an embedding call
     *     would (network, quota, auth) doesn't need a second exception type.
     */
    Verdict adjudicate(String statementA, String statementB);
}
