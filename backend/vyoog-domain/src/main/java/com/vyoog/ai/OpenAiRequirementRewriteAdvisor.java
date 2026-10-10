package com.vyoog.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private final ModelGateway gateway;
    private final ObjectMapper json;

    public OpenAiRequirementRewriteAdvisor(ModelGateway gateway, ObjectMapper json) {
        this.gateway = gateway;
        this.json = json;
    }

    @Override
    public String modelName() {
        return gateway.chatModel();
    }

    @Override
    public Suggestion suggest(String statement, Map<String, Integer> qualityBreakdown) {
        if (!gateway.configured()) {
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

        ChatReply reply = gateway.chat(ChatRequest.interactive("rewrite-suggestion", SYSTEM_PROMPT, userContent, 400, 0.2));

        JsonNode content;
        try {
            content = json.readTree(reply.content());
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
}
