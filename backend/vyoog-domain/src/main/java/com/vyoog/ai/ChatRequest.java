package com.vyoog.ai;

import java.time.Duration;

/**
 * VYB-0936: one chat completion, in provider-neutral terms.
 *
 * @param jsonObject ask the provider to reply with a single JSON object
 * @param timeout    how long one attempt may take; the gateway also enforces a total limit per {@link CallKind}
 */
public record ChatRequest(String system, String user, int maxTokens, double temperature, boolean jsonObject,
                          CallKind kind, Duration timeout) {

    public ChatRequest {
        if (system == null || user == null) throw new IllegalArgumentException("system and user text are required");
        if (maxTokens < 1) throw new IllegalArgumentException("maxTokens must be positive");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (kind == null) throw new IllegalArgumentException("kind is required");
    }

    /** A person is waiting: a plain-text reply (the caller parses it), a 12 second attempt. */
    public static ChatRequest interactive(String system, String user, int maxTokens, double temperature) {
        return new ChatRequest(system, user, maxTokens, temperature, false, CallKind.INTERACTIVE, Duration.ofSeconds(12));
    }

    /** Nobody is waiting: a JSON-object reply and the caller's own per-attempt timeout. */
    public static ChatRequest batchJson(String system, String user, int maxTokens, double temperature, Duration timeout) {
        return new ChatRequest(system, user, maxTokens, temperature, true, CallKind.BATCH, timeout);
    }

    /** Nobody is waiting: a plain-text reply (a sweep's adjudication). */
    public static ChatRequest batch(String system, String user, int maxTokens, double temperature, Duration timeout) {
        return new ChatRequest(system, user, maxTokens, temperature, false, CallKind.BATCH, timeout);
    }
}
