package com.vyoog.ai;

import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * <strong>Not a semantic embedding model.</strong> Active only while
 * {@code vyoog.ai.enabled} is false (VYB-0602's original state, and still the
 * default) — a real, working, deterministic technique: the "hashing trick"
 * (feature-hashed bag of character trigrams, signed and L2-normalised), genuinely
 * used in production text-similarity systems when a full transformer model is
 * unavailable or too costly. It captures lexical overlap — near-identical wording
 * scores high — but not paraphrase or synonymy the way a real semantic model would;
 * two requirements that say the same thing in different words will score lower here
 * than they would against a real embedding API. VYB-0600–0604's storage, staleness,
 * indexing and model-provenance machinery is all real and provider-agnostic — the
 * moment {@code vyoog.ai.enabled=true}, {@link OpenAiEmbeddingProvider} takes over
 * as the sole {@link EmbeddingProvider} bean instead, with zero other code changing.
 */
@Component
@ConditionalOnProperty(name = "vyoog.ai.enabled", havingValue = "false", matchIfMissing = true)
public class LocalHashingEmbeddingProvider implements EmbeddingProvider {

    /** Matches requirement_embedding.embedding's declared width (V001__baseline.sql). */
    public static final int DIMENSIONS = 1536;

    @Override
    public String modelName() {
        return "local-hashing-v1";
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        String normalised = normalise(text);
        if (normalised.length() >= 3) {
            for (int i = 0; i <= normalised.length() - 3; i++) {
                String trigram = normalised.substring(i, i + 3);
                int h = stableHash(trigram);
                int bucket = Math.floorMod(h, DIMENSIONS);
                // The sign bit (a second, independent hash) is the "signed" half of
                // the hashing trick — it keeps unrelated trigrams from all pushing
                // the same bucket in the same direction, which would otherwise bias
                // every vector's magnitude toward its length rather than its content.
                float sign = ((h >>> 16) & 1) == 0 ? 1f : -1f;
                vector[bucket] += sign;
            }
        }
        normalise(vector);
        return vector;
    }

    private static String normalise(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** {@code String.hashCode()} is specified by the JDK and stable across runs/JVMs — real determinism, not an accident of implementation. */
    private static int stableHash(String s) {
        return s.hashCode();
    }

    private static void normalise(float[] v) {
        double sumSquares = 0;
        for (float f : v) sumSquares += (double) f * f;
        if (sumSquares == 0) return;
        float norm = (float) Math.sqrt(sumSquares);
        for (int i = 0; i < v.length; i++) v[i] /= norm;
    }
}
