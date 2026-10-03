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
 * VYB-0612/0655: the first real {@link LlmAdjudicator} this codebase has ever had —
 * until now, {@code ConflictingRequirementsDetector} has reported itself unavailable
 * on every single sweep since it was built (session 11), because {@code
 * Optional<LlmAdjudicator>} was always empty. Nothing about the detector changes:
 * this is the one new {@code @Component} its own Javadoc already said would be
 * enough.
 *
 * <p>Same OpenAI Chat Completions shape as the other single-call advisors here —
 * same env vars, same {@code java.net.http.HttpClient}, same "refuse rather than
 * fabricate" discipline reused via {@link AiProviderUnavailableException}: not
 * configured, unreachable, an HTTP error, or a response that doesn't parse as the
 * expected shape all refuse rather than guess at a verdict.
 */
@Component
public class OpenAiLlmAdjudicator implements LlmAdjudicator {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmAdjudicator.class);

    private static final String PROMPT_VERSION = "conflict-adjudication-v1";

    private static final String SYSTEM_PROMPT = """
        You judge whether two software requirement statements actually contradict each
        other (not merely overlap or relate — a genuine logical contradiction where
        both cannot be true/satisfied at once). Respond with ONLY a JSON object, no
        markdown, no commentary:
        {"contradicts":true|false,"confidence":0.0-1.0,"explanation":"one short sentence naming the actual contradiction, or why there isn't one"}
        """;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;

    public OpenAiLlmAdjudicator(ObjectMapper json) {
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

    @Override
    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String modelAndPromptVersion() {
        return model + "/" + PROMPT_VERSION;
    }

    @Override
    public Verdict adjudicate(String statementA, String statementB) {
        if (!isConfigured()) {
            throw new AiProviderUnavailableException(
                "AI conflict adjudication is not configured (set AI_ENABLED=true and AI_API_KEY).");
        }

        String userPrompt = "Requirement A: " + statementA + "\nRequirement B: " + statementB;
        Map<String, Object> requestBody = Map.of(
            "model", model,
            "messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userPrompt)),
            "max_tokens", 150,
            "temperature", 0.1);

        String body;
        try {
            body = json.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new AiProviderUnavailableException("Could not encode the adjudication request: " + e.getMessage());
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
            log.warn("[ai] OpenAI adjudication request failed: {}", e.getMessage());
            throw new AiProviderUnavailableException("Could not reach the AI provider: " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            String detail = readErrorMessage(response.body());
            log.warn("[ai] OpenAI adjudication returned status={} detail={}", response.statusCode(), detail);
            throw new AiProviderUnavailableException("AI provider returned an error: " + detail);
        }

        JsonNode content;
        try {
            JsonNode top = json.readTree(response.body());
            String text = top.at("/choices/0/message/content").asText();
            content = json.readTree(text);
        } catch (Exception e) {
            throw new AiProviderUnavailableException("AI provider's response could not be parsed: " + e.getMessage());
        }

        if (content.path("contradicts").isMissingNode()) {
            throw new AiProviderUnavailableException("AI provider's response was missing a 'contradicts' verdict.");
        }
        boolean contradicts = content.path("contradicts").asBoolean(false);
        double confidence = content.path("confidence").asDouble(0.0);
        String explanation = content.path("explanation").asText("");
        return new Verdict(contradicts, explanation, confidence);
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
