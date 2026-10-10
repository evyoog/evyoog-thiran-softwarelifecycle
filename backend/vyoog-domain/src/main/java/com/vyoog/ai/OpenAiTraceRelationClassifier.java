package com.vyoog.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * VYB-0630 AI enrichment (2026-08-12): the first real {@link TraceRelationClassifier}
 * — same shape, env vars, and "never fake 'I don't know'" discipline as {@link
 * OpenAiLlmAdjudicator} and {@link OpenAiRequirementRewriteAdvisor}.
 */
@Component
public class OpenAiTraceRelationClassifier implements TraceRelationClassifier {

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

    private final ModelGateway gateway;
    private final ObjectMapper json;

    public OpenAiTraceRelationClassifier(ModelGateway gateway, ObjectMapper json) {
        this.gateway = gateway;
        this.json = json;
    }

    @Override
    public String modelName() {
        return gateway.chatModel();
    }

    @Override
    public List<ProposedLink> classify(String statement, List<Candidate> nearby) {
        if (!gateway.configured()) {
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

        ChatReply reply = gateway.chat(ChatRequest.interactive("trace-relation", SYSTEM_PROMPT, user.toString(), 500, 0.1));

        JsonNode content;
        try {
            content = json.readTree(reply.content());
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
}
