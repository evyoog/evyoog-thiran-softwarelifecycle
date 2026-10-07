package com.vyoog.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * VYB-0602: the real semantic embedding model {@link LocalHashingEmbeddingProvider}'s
 * own Javadoc always said would be "exactly one new {@code EmbeddingProvider} bean."
 * Active only while {@code vyoog.ai.enabled=true} — swaps in for the hashing provider
 * automatically via {@code @ConditionalOnProperty}, with zero changes to
 * {@code EmbeddingService}, the detectors, or the storage schema (OpenAI's
 * {@code text-embedding-3-small} is 1536-dimensional by default — the exact width
 * {@code requirement_embedding.embedding} already declares, so no migration is
 * needed to switch).
 *
 * <p>VYB-0936: the request is {@link ModelGateway}'s (retries, a circuit breaker for the embeddings endpoint).
 * Same "refuse rather than fabricate" discipline: not configured, unreachable, a provider error, or a response
 * that doesn't parse as expected all throw {@link AiProviderUnavailableException} — {@code EmbeddingService}
 * already catches that and warn-logs rather than failing the write that triggered it (VYB-0603), so this
 * provider never needs its own fallback-to-zero-vector logic.
 */
@Component
@ConditionalOnProperty(name = "vyoog.ai.enabled", havingValue = "true")
public class OpenAiEmbeddingProvider implements EmbeddingProvider {

    /** text-embedding-3-small's real, default output width — matches V001's vector(1536) exactly. */
    private static final int DIMENSIONS = 1536;

    private final ModelGateway gateway;

    public OpenAiEmbeddingProvider(ModelGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String modelName() {
        return gateway.embeddingModel();
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public float[] embed(String text) {
        if (!gateway.configured()) {
            throw new AiProviderUnavailableException(
                "AI embeddings are not configured (vyoog.ai.enabled is true but AI_API_KEY is unset).");
        }
        // An embedding is made when a requirement is written, so a person is waiting: the gateway's interactive policy.
        float[] vector = gateway.embed(text, CallKind.INTERACTIVE).vector();

        if (vector.length != DIMENSIONS) {
            // VYB-0619: never silently truncate/pad a dimension mismatch — that would
            // corrupt every downstream pgvector distance comparison without anyone
            // noticing. Refuse instead, naming the actual size received.
            throw new AiProviderUnavailableException(
                "AI provider returned a %d-dimension embedding, expected %d — refusing rather than reshaping it."
                    .formatted(vector.length, DIMENSIONS));
        }
        return vector;
    }
}
