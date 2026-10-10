package com.vyoog.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * VYB-0936: one chat completion, in provider-neutral terms.
 *
 * @param purpose    VYB-0939: what the call is for ("rewrite-suggestion", "document-synthesis"); recorded in the usage
 *                   ledger. Never the data sent.
 * @param jsonObject ask the provider to reply with a single JSON object
 * @param timeout    how long one attempt may take; the gateway also enforces a total limit per {@link CallKind}
 */
public record ChatRequest(String purpose, String system, String user, int maxTokens, double temperature, boolean jsonObject,
                          CallKind kind, Duration timeout) {

    public ChatRequest {
        if (purpose == null || purpose.isBlank()) throw new IllegalArgumentException("a call needs a purpose, so its usage can be told apart");
        if (system == null || user == null) throw new IllegalArgumentException("system and user text are required");
        if (maxTokens < 1) throw new IllegalArgumentException("maxTokens must be positive");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (kind == null) throw new IllegalArgumentException("kind is required");
    }

    /**
     * VYB-0939: the version of the prompt, as the first 8 hex characters of the SHA-256 of the system prompt. Editing a prompt
     * changes it, so a change in behaviour can be lined up with the version that produced it without anyone remembering to
     * bump a number. The user text is data, not part of the version.
     */
    public String promptVersion() {
        return promptVersionOf(system);
    }

    public static String promptVersionOf(String systemPrompt) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(systemPrompt.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is required of every JVM
        }
    }

    /** The same request with other user text (what remains after redaction). */
    public ChatRequest withUser(String newUser) {
        return new ChatRequest(purpose, system, newUser, maxTokens, temperature, jsonObject, kind, timeout);
    }

    /** A person is waiting: a plain-text reply (the caller parses it), a 12 second attempt. */
    public static ChatRequest interactive(String purpose, String system, String user, int maxTokens, double temperature) {
        return new ChatRequest(purpose, system, user, maxTokens, temperature, false, CallKind.INTERACTIVE, Duration.ofSeconds(12));
    }

    /** Nobody is waiting: a JSON-object reply and the caller's own per-attempt timeout. */
    public static ChatRequest batchJson(String purpose, String system, String user, int maxTokens, double temperature, Duration timeout) {
        return new ChatRequest(purpose, system, user, maxTokens, temperature, true, CallKind.BATCH, timeout);
    }

    /** Nobody is waiting: a plain-text reply (a sweep's adjudication). */
    public static ChatRequest batch(String purpose, String system, String user, int maxTokens, double temperature, Duration timeout) {
        return new ChatRequest(purpose, system, user, maxTokens, temperature, false, CallKind.BATCH, timeout);
    }
}
