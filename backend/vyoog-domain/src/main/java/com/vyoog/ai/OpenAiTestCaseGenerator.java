package com.vyoog.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** {@link TestCaseGenerator} against OpenAI. */
@Component
public class OpenAiTestCaseGenerator implements TestCaseGenerator {

    private static final String SYSTEM_PROMPT = """
        You propose test cases for a single requirement in a requirements management
        platform. A person will review, edit and individually accept or discard every
        test case you propose — you are not writing anything final, and none of it runs
        or is recorded as a result.

        You are given the requirement's key, title, statement and acceptance criteria;
        (sometimes) a list of related requirements it is trace-linked to, each labelled
        UPSTREAM (this requirement depends on it) or DOWNSTREAM (it depends on this
        requirement); and (sometimes) the messages of commits already linked to this
        requirement by a "Requirement: KEY" trailer — this platform has no source code,
        diff or file content anywhere, only that message text, so it is everything you
        will ever know about what was actually built.

        Produce two kinds of test case:

        - "INDIVIDUAL": validates the requirement's own statement and acceptance
          criteria in isolation. Be thorough, not minimal — cover every angle the given
          content actually supports, grounded only in what it says. Work through each of
          these lenses, and produce a test case for a lens only when the given content
          genuinely supports one:
            * functional correctness — the core behaviour the statement describes
              actually happening, for its stated inputs/triggers
            * field/input validation — required fields, formats, allowed values, or
              limits the statement or acceptance criteria name
            * technical edge cases (boundary values) — the smallest/largest/empty/just-
              over-the-limit values a named threshold or format implies
            * error handling (negative tests) — invalid, missing, or contradictory input
              relative to what the statement/criteria require, and what should happen
              instead of the normal outcome
            * positive tests — the straightforward case working exactly as stated,
              stated plainly so it isn't lost among the edge/negative ones
            * business-rule / outcome correctness (non-technical) — whether the outcome
              the acceptance criteria describe is the one a real user or the stated
              business rule actually needs, in plain outcome terms, not implementation
              terms
            * impact — if related requirements were given, what a real change here would
              put at risk for them (this feeds "DEPENDENCY" below, not INDIVIDUAL)
            * feasibility / rare cases — a genuinely unusual but plausible combination of
              the stated conditions occurring together, if the given content supports one
              existing; never invent a scenario the statement doesn't actually describe
          Four thin, invented test cases are worse than two solid ones — skip a lens
          with nothing real behind it rather than padding for coverage's sake.
        - "DEPENDENCY": validates this requirement working correctly together with a
          related requirement — e.g. that its behaviour still holds given what it
          depends on, or that what depends on it still receives what it needs (this is
          the impact/regression lens against a *related requirement*). Produce these
          ONLY when related requirements were given to you; if none were given, produce
          no DEPENDENCY test cases at all. Each DEPENDENCY test case's "rationale" must
          name which related requirement (by key) it concerns.

        If commit messages are given, this requirement already has real work committed
        against it. Read what those messages actually say was done (never guess beyond
        the words themselves — a message is not a diff and may be incomplete) and weave
        in regression-style INDIVIDUAL test cases that check the specific things those
        messages claim, alongside the lenses above — so accepting these tests also
        guards what was already built, not only what the statement alone implies. If no
        commit messages are given, treat this as not yet built: skip the regression-
        against-commits angle entirely (there is nothing committed to regress against)
        and rely on the statement/acceptance-criteria/related-requirement lenses above.

        For EACH test case, produce:
        - "category": exactly "INDIVIDUAL" or "DEPENDENCY".
        - "title": a short, specific name for the test case.
        - "description": a short bulleted list of concrete steps and checks, NOT a
          paragraph — one setup/action/expected-result per line, each line starting
          with "- ". E.g. "- Set up: ...\\n- Do: ...\\n- Expect: ...". Every line must be
          concrete and checkable, grounded only in what the requirement/criteria/commit
          messages state.
        - "rationale": one short sentence on why this test case matters for this
          requirement (and, for DEPENDENCY cases, which related requirement it concerns).

        Rules you must not break:
        - Never invent a fact, threshold, system name, field name or business rule that
          the statement, acceptance criteria, related requirements, or commit messages
          you were given do not already contain.
        - Never state or imply effort, duration, hours, days, sprints, story points,
          cost, budget, price, team size or difficulty. Not in any field.
        - Never claim a test ran, passed, or produced a result — you are proposing a
          test case to write, not reporting one.
        - Say nothing about this being AI-generated, about the prompt, or about the
          platform itself — write only the test case content.

        Reply with ONLY this JSON object, no markdown and no commentary:
        {"testCases":[{"category":"INDIVIDUAL or DEPENDENCY","title":"...",
        "description":"...","rationale":"..."}]}
        """;

    private final JsonModelClient chat;

    public OpenAiTestCaseGenerator(JsonModelClient chat) {
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
    public List<Suggestion> generate(RequirementInput requirement, List<RelatedRequirement> related) {
        StringBuilder user = new StringBuilder()
            .append("Requirement:\n")
            .append("key: ").append(requirement.key()).append('\n')
            .append("title: ").append(requirement.title()).append('\n')
            .append("statement: ").append(requirement.statement()).append('\n');
        if (requirement.acceptanceCriteria().isEmpty()) {
            user.append("acceptance criteria: (none recorded)\n");
        } else {
            user.append("acceptance criteria:\n");
            for (String c : requirement.acceptanceCriteria()) user.append("  - ").append(c).append('\n');
        }

        if (related.isEmpty()) {
            user.append("\nRelated requirements: none — this requirement has no trace links. ")
                .append("Produce INDIVIDUAL test cases only.\n");
        } else {
            user.append("\nRelated requirements:\n");
            for (RelatedRequirement r : related) {
                user.append("  [").append(r.direction()).append("] ").append(r.key())
                    .append(" — ").append(r.title()).append(": ").append(r.statement()).append('\n');
            }
        }

        if (requirement.linkedCommitMessages().isEmpty()) {
            user.append("\nCommits linked to this requirement: none — nothing has been committed against it yet.\n");
        } else {
            user.append("\nCommits linked to this requirement (message only — no diff/code content exists):\n");
            for (String message : requirement.linkedCommitMessages()) {
                user.append("  - ").append(message.replace("\n", " ")).append('\n');
            }
        }

        // Bumped from 1600: bullet-point descriptions covering functional/validation/
        // technical/business angles run longer per test case, and there are now
        // typically more of them per requirement.
        JsonNode result = chat.completeJson(SYSTEM_PROMPT, user.toString(), 2600, 0.3);

        List<Suggestion> out = new ArrayList<>();
        for (JsonNode node : result.path("testCases")) {
            out.add(new Suggestion(category(node), text(node, "title"), text(node, "description"), text(node, "rationale")));
        }
        return List.copyOf(out);
    }

    private static Category category(JsonNode node) {
        String raw = node.path("category").asText("");
        try {
            return Category.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        String s = value.asText("").strip();
        return s.isEmpty() ? null : s;
    }
}
