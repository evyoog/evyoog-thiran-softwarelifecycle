package com.vyoog.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** VYB-0667: {@link DocumentRelevanceTriager} against OpenAI. */
@Component
public class OpenAiDocumentRelevanceTriager implements DocumentRelevanceTriager {

    private static final String SYSTEM_PROMPT = """
        You are the relevance triage stage of a document analysis pipeline for a
        requirements management platform. You are given ONE excerpt of a larger business
        or technical document.

        Your job is to decide what in this excerpt carries real meaning for understanding
        the system, product, process, behaviour, expectations, constraints, problems or
        business needs it describes — and to discard everything else.

        DISCARD, and do not report as findings: company names, logos and branding,
        addresses, phone numbers, emails and other contact details, author names,
        document control tables, version/revision history rows, page headers and footers,
        confidentiality and copyright notices, tables of contents, generic introductions
        and scope-of-this-document paragraphs, marketing or sales language, filler,
        pleasantries, and any sentence that repeats something already stated.

        KEEP only material that means something: what the system or process does or must
        do, business rules and calculations, data entities/fields/formats/volumes,
        integrations and handoffs, constraints (performance, security, regulatory,
        platform), stated problems and current-state failures, deviations between
        expected and actual behaviour, risks and their consequences, dependencies,
        business expectations and success measures, and domain terms the document
        defines.

        Rules you must not break:
        - Every finding must quote the excerpt verbatim in "evidence". Copy the exact
          characters from the excerpt. Do not paraphrase, correct, translate or shorten
          inside "evidence".
        - "statement" is your own precise one- or two-sentence reading of what that
          evidence means in context. It must add understanding, not restate the quote.
        - Never introduce a fact, number, name, system or conclusion the excerpt does not
          contain. If the excerpt is ambiguous, say what it does say.
        - Preserve technical detail exactly: values, units, thresholds, field names,
          statuses, error codes, conditions.
        - If the whole excerpt is boilerplate, return an empty findings array. That is a
          correct and useful answer, not a failure.

        Reply with ONLY this JSON object, no markdown and no commentary:
        {"findings":[{"category":"ONE OF SYSTEM_BEHAVIOUR|BUSINESS_RULE|DATA|INTEGRATION|CONSTRAINT|PROBLEM|DEVIATION|RISK|DEPENDENCY|EXPECTATION|TERMINOLOGY",
        "statement":"what this means, 1-2 sentences","evidence":"verbatim quote from the excerpt",
        "importance":"HIGH|MEDIUM|LOW"}],
        "discardedCount":<how many blocks you discarded as noise>,
        "noiseSummary":"one short sentence naming the kinds of content you discarded, or empty if none"}
        """;

    private final JsonModelClient chat;

    public OpenAiDocumentRelevanceTriager(JsonModelClient chat) {
        this.chat = chat;
    }

    @Override
    public boolean available() {
        return chat.configured();
    }

    @Override
    public String modelName() {
        return chat.model();
    }

    @Override
    public Triage triage(String chunkText, String sourceLocation) {
        JsonNode result = chat.completeJson("document-triage",
            SYSTEM_PROMPT,
            "Excerpt location: " + sourceLocation + "\n\n" + chunkText,
            2000,
            0.1);

        List<DocumentFinding> findings = new ArrayList<>();
        for (JsonNode node : result.path("findings")) {
            findings.add(new DocumentFinding(
                node.path("category").asText(null),
                node.path("statement").asText(null),
                node.path("evidence").asText(null),
                sourceLocation,
                node.path("importance").asText("MEDIUM")));
        }
        return new Triage(
            List.copyOf(findings),
            Math.max(0, result.path("discardedCount").asInt(0)),
            result.path("noiseSummary").asText(""));
    }
}