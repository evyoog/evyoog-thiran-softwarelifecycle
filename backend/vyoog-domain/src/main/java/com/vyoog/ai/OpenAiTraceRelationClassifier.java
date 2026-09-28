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
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * VYB-0630 AI enrichment (2026-08-12): the first real {@link TraceRelationClassifier}
 * — same shape, env vars, and "never fake 'I don't know'" discipline as {@link
 * OpenAiLlmAdjudicator} and {@link OpenAiRequirementRewriteAdvisor}.
 */
@Component
public class OpenAiTraceRelationClassifier implements TraceRelationClassifier {

    private static final Logger log = LoggerFactory.getLogger(OpenAiTraceRelationClassifier.class);

    /** Mirrors {@link com.vyoog.trace.TraceLinkType} exactly — this is the caller's own enum, not guessed. */
    private static final Set<String> VALID_LINK_TYPES =
        Set.of("SATISFIES", "DERIVES", "VERIFIES", "IMPLEMENTS", "REFINES", "CONFLICTS");

    private static final String SYSTEM_PROMPT = """
        You are given a new requirement statement and a shortlist of existing
        requirements found nearby it by embedding similarity. Embedding-nearby does
        not mean related — most of the shortlist will be coincidental wording overlap,
        not a real relationship. For each existing requirement that is GENUINELY
        related, propose exactly one relation type from this set: SATISFIES, DERIVES,
        VERIFIES, IMPLEMENTS, REFINES, CONFLICTS. Omit every existing requirement that
        is not genuinely related — do not propose a link just because it was on the
        shortlist. Respond with ONLY a JSON object, no markdown, no commentary:
        {"links":[{"key":"VY-0001","linkType":"SATISFIES","rationale":"one short sentence"}]}
        An empty links array is the correct answer when nothing on the shortlist is
        genuinely related.
        """;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;

    public OpenAiTraceRelationClassifier(ObjectMapper json) {
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
    public List<ProposedLink> classify(String statement, List<Candidate> nearby) {
        if (!enabled || apiKey == null || apiKey.isBlank()) {
            throw new AiProviderUnavailableException(
                "AI trace-relation proposal is not configured (set AI_ENABLED=true and AI_API_KEY).");
        }
        if (nearby.isEmpty()) {
            return List.of();
        }

        StringBuilder user = new StringBuilder("New requirement:\n").append(statement).append("\n\nShortlist:\n");
        for (Candidate c : nearby) {
            user.append("- ").append(c.key()).append(": ").append(c.statement()).append('\n');
        }

        Map<String, Object> requestBody = Map.of(
            "model", model,
            "messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", user.toString())),
            "max_tokens", 500,
            "temperature", 0.1);

        String body;
        try {
            body = json.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new AiProviderUnavailableException("Could not encode the classification request: " + e.getMessage());
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
            log.warn("[ai] OpenAI request failed: {}", e.getMessage());
            throw new AiProviderUnavailableException("Could not reach the AI provider: " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            String detail = readErrorMessage(response.body());
            log.warn("[ai] OpenAI returned status={} detail={}", response.statusCode(), detail);
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

        JsonNode linksNode = content.path("links");
        if (!linksNode.isArray()) {
            throw new AiProviderUnavailableException("AI provider's response had no usable 'links' array.");
        }
        Set<String> knownKeys = new java.util.HashSet<>();
        for (Candidate c : nearby) knownKeys.add(c.key());

        List<ProposedLink> out = new ArrayList<>();
        for (JsonNode n : linksNode) {
            String key = n.path("key").asText(null);
            String linkType = n.path("linkType").asText(null);
            // VYB-0619's rule, reused: a link to a key that wasn't even offered, or a
            // type outside the real enum, is a malformed answer — dropped, not coerced
            // into the nearest valid-looking value.
            if (key == null || !knownKeys.contains(key)) continue;
            if (linkType == null || !VALID_LINK_TYPES.contains(linkType)) continue;
            out.add(new ProposedLink(key, linkType, n.path("rationale").asText("")));
        }
        return out;
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
