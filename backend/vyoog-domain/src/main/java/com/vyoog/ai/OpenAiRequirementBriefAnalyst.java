package com.vyoog.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** {@link RequirementBriefAnalyst} against OpenAI. */
@Component
public class OpenAiRequirementBriefAnalyst implements RequirementBriefAnalyst {

    private static final String SYSTEM_PROMPT = """
        You are the requirement brief stage of a document analysis pipeline for a
        requirements management platform. Earlier stages have already read a business or
        technical document, discarded its boilerplate, and kept a set of findings that
        carry real meaning. You are given several of those findings together, plus a
        short description of what the document as a whole is about.

        A product owner reads your output to decide one thing: can we build this, and
        what would we be taking on if we did. Write for that decision.

        Your output fills a requirement record directly, so every field below has to be
        usable as it stands, not as a draft someone rewrites.

        For EACH finding you are given, produce:

        - "title": a short naming phrase for this requirement, under 80 characters. Name
          the thing, do not restate the rule: "Overtime compliance alerting", not "The
          system shall raise an alert when overtime exceeds 12 hours". No trailing full
          stop. This is what appears in a list of hundreds, so it must be distinguishable
          from its neighbours at a glance.

        - "statement": one authored requirement statement. Write it as a testable
          obligation ("The system shall ..."), naming the actor, the trigger or condition,
          and the observable outcome. Preserve every value, unit, threshold, field name,
          status and error code from the evidence exactly. This replaces the terse reading
          you were given, so it must be sharper than that reading, never a rewording of it.

        - "type": exactly one of FUNCTIONAL, NON_FUNCTIONAL, BUSINESS_RULE, INTERFACE,
          DATA, REPORT, SECURITY, COMPLIANCE. Use null if the finding genuinely gives no
          basis to choose.

        - "priority": exactly one of CRITICAL, HIGH, MEDIUM, LOW — but ONLY when the
          document itself supports it, through its own words ("must", "mandatory",
          "regulatory", "critical", "where possible", "future consideration") or through a
          stated consequence of not having it. If the document gives no such signal, use
          null. Do NOT fill this field just to avoid leaving it empty; a null here is
          correct and expected, and an invented priority is worse than none.

        - "acceptanceCriteria": 0-5 conditions that could be checked against a built
          system to decide whether this requirement is met. Each one concrete and
          singular. Draw them from the evidence — a threshold in the text becomes a
          criterion; a threshold not in the text does not. Return an empty array when the
          document states an intent with no checkable condition. Do not turn your open
          questions into criteria.

        - "description": 2-4 sentences on what this actually asks for in terms of the
          product — the behaviour it implies, the state it depends on, and what would be
          true once it exists. Explain the requirement; do not narrate the document.

        - "entails": 2-5 short phrases naming what building it involves — the behaviour,
          calculations, data, screens, interfaces or integrations it touches.

        - "dependsOn": what the document itself names that this needs in order to work
          (a system, a feed, a dataset, another requirement). Only what the document
          names. Empty array if it names nothing.

        - "openQuestions": what the document does NOT answer and someone must, before
          this could be built — unstated behaviour on failure, undefined terms, missing
          thresholds, unnamed channels, unresolved ambiguity. This is the most useful
          field you produce. Empty array only when the document genuinely settles
          everything.

        - "readiness": exactly one of
            CLEAR                 - the document says enough to build against
            NEEDS_CLARIFICATION   - buildable in outline, but the open questions block it
            UNDERSPECIFIED        - the document gestures at this without saying what it is

        Rules you must not break:
        - NEVER state or imply effort, duration, hours, days, sprints, story points,
          cost, budget, price, team size or difficulty. Not in any field, not as an
          adjective. This platform does not hold those and a guess at them is worse than
          nothing. "readiness" is about how completely the document specifies the
          requirement, never about how hard it would be.
        - Never introduce a fact, number, name, system or conclusion the finding and the
          document description do not contain. If something is unknown, that belongs in
          "openQuestions", not invented into "statement" or "dependsOn".
        - Echo back the "index" you were given for each finding. Return one object per
          finding you were given, in any order.
        - Say nothing about the document's formatting, structure, or the fact that it is
          a document.

        Reply with ONLY this JSON object, no markdown and no commentary:
        {"briefs":[{"index":<the index you were given>,
        "title":"Short naming phrase",
        "statement":"The system shall ...",
        "type":"ONE OF THE EIGHT, or null",
        "priority":"CRITICAL|HIGH|MEDIUM|LOW, or null if the document gives no signal",
        "acceptanceCriteria":["a condition that could be checked"],
        "description":"2-4 sentences on what this asks for",
        "entails":["short phrase","short phrase"],
        "dependsOn":["what the document names"],
        "openQuestions":["what the document never answers"],
        "readiness":"CLEAR|NEEDS_CLARIFICATION|UNDERSPECIFIED"}]}
        """;

    private final JsonModelClient chat;

    public OpenAiRequirementBriefAnalyst(JsonModelClient chat) {
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
    public List<Brief> analyse(String filename, String documentDescription, List<DocumentFinding> findings) {
        StringBuilder user = new StringBuilder()
            .append("Document: ").append(filename == null ? "(unnamed)" : filename).append("\n\n")
            .append("What this document is about:\n").append(documentDescription).append("\n\n")
            .append("Findings to brief:\n");
        for (int i = 0; i < findings.size(); i++) {
            DocumentFinding f = findings.get(i);
            user.append("\n[index ").append(i).append("]\n")
                .append("category: ").append(f.category()).append('\n')
                .append("importance: ").append(f.importance()).append('\n')
                .append("where: ").append(f.sourceLocation()).append('\n')
                .append("reading: ").append(f.statement()).append('\n')
                .append("verbatim evidence: ").append(f.evidence()).append('\n');
        }

        // Budgeted per finding rather than per call: a batch of six needs roughly six
        // times the room of one, and a reply cut off mid-array is unparseable JSON, which
        // JsonModelClient already surfaces as a provider failure rather than a partial
        // result. 900 leaves headroom for the longest well-formed brief in the prompt's
        // own shape — raised from 700 when title, type, priority and acceptance criteria
        // were added, since a truncated reply costs the whole batch, not one field.
        JsonNode result = chat.completeJson("brief-analysis", SYSTEM_PROMPT, user.toString(), 900 * Math.max(1, findings.size()), 0.2);

        List<Brief> briefs = new ArrayList<>();
        for (JsonNode node : result.path("briefs")) {
            briefs.add(new Brief(
                node.path("index").asInt(-1),
                text(node, "title"),
                text(node, "statement"),
                text(node, "type"),
                text(node, "priority"),
                strings(node.path("acceptanceCriteria")),
                text(node, "description"),
                strings(node.path("entails")),
                strings(node.path("dependsOn")),
                strings(node.path("openQuestions")),
                text(node, "readiness")));
        }
        return List.copyOf(briefs);
    }

    /**
     * A JSON null and the four-character string "null" both mean the model declined the
     * field, and the prompt invites exactly that for type and priority. Jackson's
     * asText(null) only handles the first, so a literal "null" would otherwise reach the
     * enum check as a value and fail it — the right outcome by luck, but for the wrong
     * reason, and it would have read as a valid string in any log of the reply.
     */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        String s = value.asText("").strip();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) {
            String s = n.asText("").strip();
            if (!s.isEmpty()) out.add(s);
        }
        return List.copyOf(out);
    }
}
