package com.vyoog.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>Same outbound-HTTP convention as every other AI call in this codebase
 * ({@code java.net.http.HttpClient}, not a new client library) and the same
 * "refuse rather than fabricate" discipline: not configured, unreachable, an HTTP
 * error, or a response that doesn't parse as expected all throw {@link
 * AiProviderUnavailableException} — {@code EmbeddingService} already catches that
 * and warn-logs rather than failing the write that triggered it (VYB-0603), so this
 * provider never needs its own fallback-to-zero-vector logic.
 */
@Component
@ConditionalOnProperty(name = "vyoog.ai.enabled", havingValue = "true")
public class OpenAiEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingProvider.class);

    /** text-embedding-3-small's real, default output width — matches V001's vector(1536) exactly. */
    private static final int DIMENSIONS = 1536;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;

    public OpenAiEmbeddingProvider(ObjectMapper json) {
        this.json = json;
    }

    @Value("${vyoog.ai.embeddings-url:https://api.openai.com/v1/embeddings}")
    private String apiUrl;

    @Value("${vyoog.ai.api-key:}")
    private String apiKey;

    @Value("${vyoog.ai.embeddings-model:text-embedding-3-small}")
    private String model;

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public float[] embed(String text) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiProviderUnavailableException(
                "AI embeddings are not configured (vyoog.ai.enabled is true but AI_API_KEY is unset).");
        }

        String body;
        try {
            body = json.writeValueAsString(Map.of("model", model, "input", text == null ? "" : text));
        } catch (Exception e) {
            throw new AiProviderUnavailableException("Could not encode the embeddings request: " + e.getMessage());
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            log.warn("[ai] OpenAI embeddings request failed: {}", e.getMessage());
            throw new AiProviderUnavailableException("Could not reach the AI provider: " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            String detail = readErrorMessage(response.body());
            log.warn("[ai] OpenAI embeddings returned status={} detail={}", response.statusCode(), detail);
            throw new AiProviderUnavailableException("AI provider returned an error: " + detail);
        }

        JsonNode embeddingNode;
        try {
            JsonNode top = json.readTree(response.body());
            embeddingNode = top.at("/data/0/embedding");
            if (!embeddingNode.isArray()) {
                throw new AiProviderUnavailableException("AI provider's response had no embedding array.");
            }
        } catch (AiProviderUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new AiProviderUnavailableException("AI provider's response could not be parsed: " + e.getMessage());
        }

        if (embeddingNode.size() != DIMENSIONS) {
            // VYB-0619: never silently truncate/pad a dimension mismatch — that would
            // corrupt every downstream pgvector distance comparison without anyone
            // noticing. Refuse instead, naming the actual size received.
            throw new AiProviderUnavailableException(
                "AI provider returned a %d-dimension embedding, expected %d — refusing rather than reshaping it."
                    .formatted(embeddingNode.size(), DIMENSIONS));
        }
        float[] vector = new float[DIMENSIONS];
        for (int i = 0; i < DIMENSIONS; i++) vector[i] = (float) embeddingNode.get(i).asDouble();
        return vector;
    }

    private String readErrorMessage(String body) {
        try {
            JsonNode node = json.readTree(body);
            JsonNode message = node.at("/error/message");
            return message.isMissingNode() ? body : message.asText();
        } catch (Exception e) {
            return body;
        }
    }
}
