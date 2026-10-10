package com.vyoog.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** {@link RequirementElaborationAdvisor} against OpenAI. */
@Component
public class OpenAiRequirementElaborationAdvisor implements RequirementElaborationAdvisor {

    private static final String SYSTEM_PROMPT = """
        You write the developer-facing elaboration section of an implementation brief for
        a requirements management platform. Every requirement you are given has already
        been authored by a person and formally approved — you are not proposing a
        requirement, judging its quality, or suggesting a replacement wording for it. Your
        only job is to expand what it already says into more detailed prose so the
        developer building it understands it faster than by reading the bare statement
        alone.

        For EACH requirement you are given, produce:

        - "detail": 3-6 sentences of plain prose. Restate the trigger or condition, the
          system's expected behaviour, and what "done" looks like, in more detail than the
          one-line statement gives. You may name concrete edge cases or implementation
          implications ONLY when they follow directly from the statement or its
          acceptance criteria (e.g. an acceptance criterion naming a threshold implies a
          boundary case at that threshold). Do not restate the acceptance criteria as a
          list; write connected prose that a developer reads once.

        Rules you must not break:
        - Never introduce a fact, number, threshold, system name, field name or business
          rule that the statement and acceptance criteria you were given do not already
          contain. If the requirement is thin, write a thin elaboration — inventing detail
          to sound thorough is worse than a short, honest one.
        - Never state or imply effort, duration, hours, days, sprints, story points, cost,
          budget, price, team size or difficulty. Not in any field, not as an adjective.
        - Never propose a different requirement, a rewritten statement, a priority, or a
          type. This is elaboration, not a second draft.
        - Echo back the "index" you were given for each requirement. Return one object per
          requirement you were given, in any order.
        - Say nothing about this being AI-generated, about the prompt, or about the
          platform itself — write only the elaboration content.

        Reply with ONLY this JSON object, no markdown and no commentary:
        {"elaborations":[{"index":<the index you were given>,
        "detail":"3-6 sentences of detailed prose"}]}
        """;

    private final JsonModelClient chat;

    public OpenAiRequirementElaborationAdvisor(JsonModelClient chat) {
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
    public List<Elaboration> elaborate(String applicationName, List<Input> requirements) {
        StringBuilder user = new StringBuilder()
            .append("Application: ").append(applicationName == null ? "(unnamed)" : applicationName).append("\n\n")
            .append("Requirements to elaborate:\n");
        for (int i = 0; i < requirements.size(); i++) {
            Input r = requirements.get(i);
            user.append("\n[index ").append(i).append("]\n")
                .append("key: ").append(r.key()).append('\n')
                .append("title: ").append(r.title()).append('\n')
                .append("statement: ").append(r.statement()).append('\n');
            if (r.acceptanceCriteria().isEmpty()) {
                user.append("acceptance criteria: (none recorded)\n");
            } else {
                user.append("acceptance criteria:\n");
                for (String c : r.acceptanceCriteria()) user.append("  - ").append(c).append('\n');
            }
        }

        // Budgeted per requirement, same reasoning as RequirementBriefAnalyst: a batch of
        // eight needs roughly eight times the room of one, and a reply cut off mid-array
        // is unparseable JSON either way.
        JsonNode result = chat.completeJson("brief-elaboration", SYSTEM_PROMPT, user.toString(), 400 * Math.max(1, requirements.size()), 0.3);

        List<Elaboration> out = new ArrayList<>();
        for (JsonNode node : result.path("elaborations")) {
            String detail = text(node, "detail");
            out.add(new Elaboration(node.path("index").asInt(-1), detail));
        }
        return List.copyOf(out);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        String s = value.asText("").strip();
        return s.isEmpty() ? null : s;
    }
}
