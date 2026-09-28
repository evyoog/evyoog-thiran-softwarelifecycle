package com.vyoog.ai;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LocalHashingEmbeddingProviderTest {

    private final LocalHashingEmbeddingProvider provider = new LocalHashingEmbeddingProvider();

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot; // both are already L2-normalised, so the dot product is the cosine similarity
    }

    @Test
    void producesTheDeclaredDimensionCount() {
        assertThat(provider.embed("A requirement about something").length).isEqualTo(LocalHashingEmbeddingProvider.DIMENSIONS);
    }

    @Test
    void sameTextProducesTheSameVectorEveryTime() {
        float[] a = provider.embed("The system shall reject an invalid login.");
        float[] b = provider.embed("The system shall reject an invalid login.");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void vectorsAreL2Normalised() {
        float[] v = provider.embed("Some fairly ordinary requirement statement.");
        double sumSquares = 0;
        for (float f : v) sumSquares += (double) f * f;
        assertThat(Math.sqrt(sumSquares)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void nearIdenticalTextScoresHigherThanUnrelatedText() {
        float[] original = provider.embed("The system shall lock an account after five failed login attempts.");
        float[] nearDuplicate = provider.embed("The system shall lock the account after five failed login attempts.");
        float[] unrelated = provider.embed("Invoices shall be exported as PDF at the end of each billing cycle.");

        double similarToDuplicate = cosine(original, nearDuplicate);
        double similarToUnrelated = cosine(original, unrelated);

        assertThat(similarToDuplicate).isGreaterThan(similarToUnrelated);
        assertThat(similarToDuplicate).isGreaterThan(0.8); // near-identical wording, one word swapped
    }

    @Test
    void emptyTextProducesAZeroVectorRatherThanFailing() {
        float[] v = provider.embed("");
        for (float f : v) assertThat(f).isZero();
    }

    @Test
    void reportsItsOwnModelName() {
        assertThat(provider.modelName()).isEqualTo("local-hashing-v1");
    }
}
