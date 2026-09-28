package com.vyoog.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * VYB-0794: the first real {@link RequirementRewriteAdvisor} — an OpenAI Chat
 * Completions call, same {@code java.net.http.HttpClient} convention and
 * {@code vyoog.ai.*} configuration keys every other OpenAI-backed class here
 * already established, so this is the third implementation reusing the same
 * account/quota rather than standing up a separate integration.
 *
 * <p>Same "never fake it" discipline as every other AI integration in this package:
 * unavailable, misconfigured, or an unparseable response all throw
 * {@link AiProviderUnavailableException} rather than falling back to some
 * template-based rewrite that isn't actually AI-generated.
 */
@Component
public class OpenAiRequirementRewriteAdvisor implements RequirementRewriteAdvisor {

    private static final Logger log = LoggerFactory.getLogger(OpenAiRequirementRewriteAdvisor.class);

    private static final String SYSTEM_PROMPT = """
        You improve a single software requirement statement. You will be given the
        statement and a scoring breakdown from a deterministic linter (negative
        numbers are penalties: "wording" flags ambiguous terms, "criteria" flags too
        few/no acceptance criteria, "traceability" flags no upstream link, "length"
        flags a statement that's too short). Rewrite the statement to address only
        what that breakdown actually penalized — do not invent new scope or change
        the requirement's meaning. Respond with ONLY a JSON object, no markdown, no
        commentary: {"rewrittenStatement":"...","changes":["one short sentence per
        concrete fix, e.g. 'Replaced the ambiguous term \\'fast\\' with a specific
        response-time bound.'"]}
        """;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;

    public OpenAiRequirementRewriteAdvisor(ObjectMapper json) {
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
    public String modelName() {
        return model;
    }

    @Override
    public Suggestion suggest(String statement, Map<String, Integer> qualityBreakdown) {
        if (!enabled || apiKey == null || apiKey.isBlank()) {
            throw new AiProviderUnavailableException(
                "AI rewrite suggestions are not configured (set AI_ENABLED=true and AI_API_KEY).");
        }
        if (statement == null || statement.isBlank()) {
            throw new AiProviderUnavailableException("Nothing to rewrite — the statement is empty.");
        }

        String userContent;
        try {
            userContent = json.writeValueAsString(Map.of("statement", statement, "qualityBreakdown", qualityBreakdown));
        } catch (Exception e) {
            throw new AiProviderUnavailableException("Could not encode the rewrite request: " + e.getMessage());
        }

        Map<String, Object> requestBody = Map.of(
            "model", model,
            "messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userContent)),
            "max_tokens", 400,
            "temperature", 0.2);

        String body;
        try {
            body = json.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new AiProviderUnavailableException("Could not encode the rewrite request: " + e.getMessage());
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
            log.warn("[ai] OpenAI rewrite request failed: {}", e.getMessage());
            throw new AiProviderUnavailableException("Could not reach the AI provider: " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            String detail = readErrorMessage(response.body());
            log.warn("[ai] OpenAI rewrite returned status={} detail={}", response.statusCode(), detail);
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

        String rewritten = content.path("rewrittenStatement").asText(null);
        if (rewritten == null || rewritten.isBlank()) {
            // VYB-0619-style refusal: never fabricate a rewrite the model itself didn't produce.
            throw new AiProviderUnavailableException("AI provider did not return a rewritten statement.");
        }
        List<String> changes = new ArrayList<>();
        JsonNode changesNode = content.path("changes");
        if (changesNode.isArray()) {
            changesNode.forEach(n -> changes.add(n.asText()));
        }
        return new Suggestion(rewritten, changes);
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
