package com.vyoog.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * VYB-0612/0655: the first real {@link LlmAdjudicator} this codebase has ever had —
 * until now, {@code ConflictingRequirementsDetector} has reported itself unavailable
 * on every single sweep since it was built (session 11), because {@code
 * Optional<LlmAdjudicator>} was always empty. Nothing about the detector changes:
 * this is the one new {@code @Component} its own Javadoc already said would be
 * enough.
 *
 * <p>VYB-0936: the request itself is {@link ModelGateway}'s, with its retries and circuit breaker. Same
 * "refuse rather than fabricate" discipline via {@link AiProviderUnavailableException}: not configured,
 * unreachable, a provider error, or a response that doesn't parse as the expected shape all refuse rather
 * than guess at a verdict.
 */
@Component
public class OpenAiLlmAdjudicator implements LlmAdjudicator {

    private static final String PROMPT_VERSION = "conflict-adjudication-v1";

    private static final String SYSTEM_PROMPT = """
        You judge whether two software requirement statements actually contradict each
        other (not merely overlap or relate — a genuine logical contradiction where
        both cannot be true/satisfied at once). Respond with ONLY a JSON object, no
        markdown, no commentary:
        {"contradicts":true|false,"confidence":0.0-1.0,"explanation":"one short sentence naming the actual contradiction, or why there isn't one"}
        """;

    private final ModelGateway gateway;
    private final ObjectMapper json;

    public OpenAiLlmAdjudicator(ModelGateway gateway, ObjectMapper json) {
        this.gateway = gateway;
        this.json = json;
    }

    @Override
    public boolean isConfigured() {
        return gateway.configured();
    }

    @Override
    public String modelAndPromptVersion() {
        return gateway.chatModel() + "/" + PROMPT_VERSION;
    }

    @Override
    public Verdict adjudicate(String statementA, String statementB) {
        if (!isConfigured()) {
            throw new AiProviderUnavailableException(
                "AI conflict adjudication is not configured (set AI_ENABLED=true and AI_API_KEY).");
        }

        String userPrompt = "Requirement A: " + statementA + "\nRequirement B: " + statementB;
        // A sweep nobody is watching: the gateway retries it harder than an interactive call.
        ChatReply reply = gateway.chat(ChatRequest.batch(SYSTEM_PROMPT, userPrompt, 150, 0.1, Duration.ofSeconds(20)));

        JsonNode content;
        try {
            content = json.readTree(reply.content());
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
}
