package com.vyoog.ai;

/**
 * VYB-0600/0603/0602: whatever actually turns text into a vector. Two implementations
 * exist, {@link LocalHashingEmbeddingProvider} and {@link OpenAiEmbeddingProvider},
 * mutually exclusive via {@code @ConditionalOnProperty} on {@code vyoog.ai.enabled} —
 * exactly one is ever registered, so nothing in {@link EmbeddingService} or the
 * detectors needs to know which is active.
 */
public interface EmbeddingProvider {

    /** VYB-0600 AC1/VYB-0604: recorded on every embedding, so a model change is visible, not silent drift. */
    String modelName();

    /** Must match {@code requirement_embedding.embedding}'s declared width (V001__baseline.sql: {@code vector(1536)}). */
    int dimensions();

    /**
     * @throws AiProviderUnavailableException VYB-0603: the provider couldn't run —
     *     network down, quota exhausted, whatever. Never returns a zero vector or
     *     any other value standing in for "I don't know."
     */
    float[] embed(String text);
}
