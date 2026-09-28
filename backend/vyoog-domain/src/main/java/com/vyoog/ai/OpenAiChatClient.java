package com.vyoog.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * VYB-0667: one OpenAI Chat Completions call that returns a JSON object, shared by the
 * three document-analysis agents.
 *
 * <p>The five {@code OpenAi*} classes that predate this one each carry their own copy
 * of this request/response block. A fourth, fifth and sixth copy for the agents below
 * would have been the point where a fix to timeout handling or error reading had to be
 * made in eight places, so the new agents share this instead. The existing five are
 * deliberately left alone — rewriting working, tested provider calls is not part of a
 * document-analysis requirement, and this class was written to match their behaviour
 * exactly (same env config, same {@link AiProviderUnavailableException} on every
 * failure path, same refusal to invent a fallback answer) so that a later pass can move
 * them onto it without changing what they do.
 */
@Component
public class OpenAiChatClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatClient.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;

    public OpenAiChatClient(ObjectMapper json) {
        this.json = json;
    }

    @Value("${vyoog.ai.enabled:false}")
    private boolean enabled;

    @Value("${vyoog.ai.api-url:https://api.openai.com/v1/chat/completions}")
    private String apiUrl;

    @Value("${vyoog.ai.api-key:}")
    private String apiKey;

    @Value("${vyoog.ai.model:gpt-4o-mini}")
    private String model;

    /**
     * Document analysis reads far more text per call than a single-statement classifier,
     * so its timeout is its own setting rather than the 20s the per-statement callers
     * use. A synthesis pass over a long specification legitimately takes longer than a
     * type classification, and timing it out would look identical to a provider outage.
     */
    @Value("${vyoog.ai.analysis-timeout-seconds:90}")
    private int timeoutSeconds;

    public String model() {
        return model;
    }

    public boolean configured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /**
     * @param maxTokens hard cap on the reply; a truncated reply is unparseable JSON and
     *                  therefore surfaces as an {@link AiProviderUnavailableException}
     *                  rather than as a half-read result the caller might treat as whole.
     * @return the parsed JSON object the model was told to return
     */
    public JsonNode completeJson(String systemPrompt, String userContent, int maxTokens, double temperature) {
        if (!configured()) {
            throw new AiProviderUnavailableException(
                "AI document analysis is not configured (set AI_ENABLED=true and AI_API_KEY).");
        }

        Map<String, Object> requestBody = Map.of(
            "model", model,
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userContent)),
            "response_format", Map.of("type", "json_object"),
            "max_tokens", maxTokens,
            "temperature", temperature);

        String body;
        try {
            body = json.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new AiProviderUnavailableException("Could not encode the analysis request: " + e.getMessage());
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("[ai] OpenAI request failed: {}", e.getMessage());
            throw new AiProviderUnavailableException("Could not reach the AI provider: " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            String detail = readErrorMessage(response.body());
            log.warn("[ai] OpenAI returned status={} detail={}", response.statusCode(), detail);
            throw new AiProviderUnavailableException("AI provider returned an error: " + detail);
        }

        try {
            JsonNode top = json.readTree(response.body());
            String finishReason = top.at("/choices/0/finish_reason").asText("");
            if ("length".equals(finishReason)) {
                // A reply cut off at max_tokens is not a smaller answer, it is a broken
                // one — half a JSON document. Say so rather than failing at parse with a
                // message that reads like the provider misbehaved.
                throw new AiProviderUnavailableException(
                    "AI provider's reply was cut off at the token limit before it finished.");
            }
            return json.readTree(top.at("/choices/0/message/content").asText());
        } catch (AiProviderUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new AiProviderUnavailableException("AI provider's response could not be parsed: " + e.getMessage());
        }
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