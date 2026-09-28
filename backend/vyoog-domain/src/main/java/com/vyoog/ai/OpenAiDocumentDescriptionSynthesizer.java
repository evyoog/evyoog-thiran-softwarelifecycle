package com.vyoog.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** VYB-0667: {@link DocumentDescriptionSynthesizer} against OpenAI. */
@Component
public class OpenAiDocumentDescriptionSynthesizer implements DocumentDescriptionSynthesizer {

    private static final String SYSTEM_PROMPT = """
        You are the synthesis stage of a document analysis pipeline. You are given the
        findings an earlier stage kept from a business or technical document, each with
        the verbatim evidence it came from and where in the document it was found. You
        never see the document itself — the findings are all there is.

        Write a comprehensive, detailed, professional description of what the document is
        actually about and what it means for the business and for the system it concerns.

        What the description must do:
        - Explain the subject: what system, product, process or change the document
          concerns, and in what business context.
        - Connect related findings across the document rather than listing them. Where a
          stated problem, a rule, a constraint and an expectation concern the same thing,
          say so and explain the relationship.
        - Preserve technical specifics exactly: values, thresholds, units, field and
          status names, conditions, error cases, formats, volumes.
        - Cover behaviour and expectations, business rules, data, integrations,
          constraints, stated problems and deviations, dependencies, risks and their
          business impact — but only where the findings support them. Say nothing about a
          dimension the findings are silent on.
        - Read as flowing professional prose in several paragraphs, organised by what the
          document is about rather than by the order material happened to appear in.

        What the description must never do:
        - Never state a fact, number, name, cause, benefit or conclusion the findings do
          not contain. No filling gaps from general knowledge of similar systems.
        - Never mention the company, authors, document control, versioning, confidentiality
          or any other administrative surround.
        - Never repeat the same point in different words, and never open with a preamble
          about the document being a document.
        - Never soften a stated problem or invent a resolution the findings do not state.

        Length follows the material: a document with a lot of substance deserves a long,
        dense description; a thin one must not be padded to look substantial.

        Reply with ONLY this JSON object, no markdown fences and no commentary:
        {"description":"the full description, paragraphs separated by \\n\\n",
        "themes":["3-8 short phrases naming what this document is really about"]}
        """;

    private final OpenAiChatClient chat;

    public OpenAiDocumentDescriptionSynthesizer(OpenAiChatClient chat) {
        this.chat = chat;
    }

    @Override
    public String modelName() {
        return chat.model();
    }

    @Override
    public Synthesis synthesize(String filename, List<DocumentFinding> findings, List<String> revisionGuidance) {
        StringBuilder user = new StringBuilder();
        user.append("Source file: ").append(filename).append("\n");
        user.append("Findings kept from the document (").append(findings.size()).append("):\n\n");
        for (DocumentFinding f : findings) {
            user.append("- [").append(f.category()).append('/').append(f.importance()).append("] ")
                .append(f.statement())
                .append("\n  evidence: \"").append(f.evidence()).append("\"")
                .append("\n  found at: ").append(f.sourceLocation()).append("\n\n");
        }

        if (revisionGuidance != null && !revisionGuidance.isEmpty()) {
            // The second pass is not a fresh attempt: it is the previous draft's own
            // unsupported claims handed back, so the model removes or corrects exactly
            // those rather than rewriting a description that was mostly right.
            user.append("""

                Your previous draft contained the following claims that could not be traced to any
                finding above. Rewrite the description so that each of them is either removed or
                restated to say only what the findings support. Change nothing else.

                """);
            user.append(revisionGuidance.stream().map(c -> "- " + c).collect(Collectors.joining("\n")));
        }

        JsonNode result = chat.completeJson(SYSTEM_PROMPT, user.toString(), 4000, 0.2);

        String description = result.path("description").asText("");
        if (description.isBlank()) {
            throw new AiProviderUnavailableException("AI provider returned an empty description.");
        }
        List<String> themes = new ArrayList<>();
        for (JsonNode t : result.path("themes")) {
            String value = t.asText("");
            if (!value.isBlank()) themes.add(value);
        }
        return new Synthesis(description, List.copyOf(themes));
    }
}