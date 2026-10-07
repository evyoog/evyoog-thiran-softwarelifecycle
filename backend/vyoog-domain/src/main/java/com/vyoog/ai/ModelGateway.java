package com.vyoog.ai;

/**
 * VYB-0936 (F28): the one door every model call goes through. A provider is an implementation of this interface
 * ({@link OpenAiGateway} is the first); nothing else in the codebase builds a request to a model provider, so
 * timeouts, retries and the circuit breaker are written and fixed once.
 *
 * <p>Every failure, whatever its cause (not configured, unreachable, a provider error, a retry budget spent, a
 * breaker that is open, a reply that cannot be read), is an {@link AiProviderUnavailableException} with a reason
 * in words. Nothing here returns a stand-in answer: "unknown" is never faked as a zero vector or an empty reply.
 */
public interface ModelGateway {

    /** True when AI is switched on and a key is present. False means every call refuses without sending anything. */
    boolean configured();

    /** The chat model that calls will use. */
    String chatModel();

    /** The embedding model that calls will use. */
    String embeddingModel();

    ChatReply chat(ChatRequest request);

    EmbeddingReply embed(String text, CallKind kind);
}
