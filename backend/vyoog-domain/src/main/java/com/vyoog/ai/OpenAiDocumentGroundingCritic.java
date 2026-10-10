package com.vyoog.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** VYB-0667: {@link DocumentGroundingCritic} against OpenAI. */
@Component
public class OpenAiDocumentGroundingCritic implements DocumentGroundingCritic {

    private static final String SYSTEM_PROMPT = """
        You are the verification stage of a document analysis pipeline. You are given a
        draft description and the findings it was supposed to be built from, each with the
        verbatim evidence it came from.

        Check the description claim by claim. A claim is supported only if the findings
        contain it — not if it is plausible, not if it is what a system like this usually
        does, not if it follows from industry knowledge. Treat these as unsupported:
        invented numbers, thresholds, names or systems; causes and consequences the
        findings do not state; benefits, intentions or outcomes attributed without
        evidence; a technical detail changed from the one in the evidence; and any
        statement that generalises beyond what the findings say.

        Report each unsupported claim as the exact sentence or clause from the description
        that carries it, so it can be found and fixed. Do not report matters of style,
        length, ordering or wording. If everything is supported, return an empty array —
        do not manufacture a finding to look thorough.

        Reply with ONLY this JSON object, no markdown and no commentary:
        {"unsupportedClaims":["the exact claim text from the description", "..."],
        "guidance":"one short sentence on the pattern behind them, or empty if none"}
        """;

    private final JsonModelClient chat;

    public OpenAiDocumentGroundingCritic(JsonModelClient chat) {
        this.chat = chat;
    }

    @Override
    public String modelName() {
        return chat.model();
    }

    @Override
    public Critique critique(String description, List<DocumentFinding> findings) {
        StringBuilder user = new StringBuilder();
        user.append("DRAFT DESCRIPTION:\n").append(description).append("\n\nFINDINGS:\n\n");
        for (DocumentFinding f : findings) {
            user.append("- [").append(f.category()).append("] ").append(f.statement())
                .append("\n  evidence: \"").append(f.evidence()).append("\"\n\n");
        }

        JsonNode result = chat.completeJson("document-critique", SYSTEM_PROMPT, user.toString(), 1500, 0.0);

        List<String> claims = new ArrayList<>();
        for (JsonNode c : result.path("unsupportedClaims")) {
            String value = c.asText("");
            if (!value.isBlank()) claims.add(value);
        }
        return new Critique(List.copyOf(claims), result.path("guidance").asText(""));
    }
}