package com.vyoog.brief;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * VYB-0450–0454/0457: pure function from scope + target to markdown — no repository,
 * no clock call of its own (the caller passes {@code asOf}), so the same input always
 * produces the same output (VYB-0450 AC2). The three targets vary real content, not
 * just a label (VYB-0451): section zero is rewritten per target (AC3), the
 * commit-trailer block is omitted for {@code HUMAN} (AC1), and the test-naming
 * convention appears only for {@code CLAUDE_CODE} (AC2).
 */
public final class BriefContentGenerator {

    private static final List<String> CATEGORY_ORDER =
        List.of("Functional", "Non-functional", "Business rule", "Other");

    private BriefContentGenerator() {}

    /**
     * Sorts VY-2 before VY-10.
     *
     * <p>Plain string ordering put VY-1, VY-10, VY-11 above VY-2, because "1" sorts before
     * "2" character by character. Determinism (VYB-0450 AC2) is unaffected — this is still
     * a total order over the same input, just the one a reader expects. Falls back to the
     * whole key for anything not shaped like PREFIX-digits, so an unusual key still sorts
     * somewhere stable rather than throwing.
     */
    private static long keyOrder(BriefRequirementView r) {
        String key = r.key() == null ? "" : r.key();
        int dash = key.lastIndexOf('-');
        if (dash < 0 || dash == key.length() - 1) return Long.MAX_VALUE;
        try {
            return Long.parseLong(key.substring(dash + 1));
        } catch (NumberFormatException notNumbered) {
            return Long.MAX_VALUE;
        }
    }

    /** Every section — what callers got before sections were selectable, and still the default. */
    public static String generate(
            String applicationName, List<String> capabilityNames, List<BriefRequirementView> requirements,
            BriefTarget target, String developerName, Instant asOf) {
        return generate(applicationName, capabilityNames, requirements, target, developerName, asOf,
            BriefSection.ALL, Map.of());
    }

    public static String generate(
            String applicationName, List<String> capabilityNames, List<BriefRequirementView> requirements,
            BriefTarget target, String developerName, Instant asOf, Set<BriefSection> sections) {
        return generate(applicationName, capabilityNames, requirements, target, developerName, asOf,
            sections, Map.of());
    }

    /**
     * @param sections which optional parts to include. Null or empty means all of them,
     *     so an older caller and a caller that deliberately cleared every box are not the
     *     same thing — the latter still gets the header, scope line and requirement
     *     bodies, which are never optional.
     */
    /**
     * @param excludedByStatus how many requirements under the same capabilities were left
     *     out for not being APPROVED, keyed by the status they are in. Reported rather
     *     than dropped: a two-requirement brief drawn from forty must not read like a
     *     brief for a two-requirement capability.
     */
    public static String generate(
            String applicationName, List<String> capabilityNames, List<BriefRequirementView> requirements,
            BriefTarget target, String developerName, Instant asOf, Set<BriefSection> sections,
            Map<String, Long> excludedByStatus) {
        return generate(applicationName, capabilityNames, requirements, target, developerName, asOf,
            sections, excludedByStatus, 0L);
    }

    /**
     * @param excludedNoTestCase VYB-0831: how many otherwise-approved requirements under
     *     the same capabilities were left out for having no test case yet — the same
     *     Principle 8 reporting as {@code excludedByStatus}, kept separate because it's a
     *     different reason to be left out, not another status.
     */
    public static String generate(
            String applicationName, List<String> capabilityNames, List<BriefRequirementView> requirements,
            BriefTarget target, String developerName, Instant asOf, Set<BriefSection> sections,
            Map<String, Long> excludedByStatus, long excludedNoTestCase) {
        Set<BriefSection> on = sections == null || sections.isEmpty() ? BriefSection.ALL : sections;
        Map<String, Long> excluded = excludedByStatus == null ? Map.of() : excludedByStatus;

        // VYB-0450 AC2: fixed ordering is what makes this deterministic, not the
        // absence of a timestamp — the same scope always groups and sorts the same way.
        List<BriefRequirementView> sorted = requirements.stream()
            .sorted(Comparator.comparing(BriefContentGenerator::keyOrder)
                .thenComparing(BriefRequirementView::key))
            .toList();
        Map<String, List<BriefRequirementView>> byCategory = new LinkedHashMap<>();
        for (String cat : CATEGORY_ORDER) byCategory.put(cat, new java.util.ArrayList<>());
        for (BriefRequirementView r : sorted) byCategory.get(r.category()).add(r);

        StringBuilder md = new StringBuilder();
        int section = 0;
        header(md, applicationName, capabilityNames, target, developerName, asOf, sorted.size(), excluded, excludedNoTestCase);
        // Numbered as they are emitted, not by a fixed index: a brief with the category
        // review switched off must not jump from §0 to §2 and leave the reader hunting
        // for a section that was never written.
        if (on.contains(BriefSection.CONTEXT)) contextSection(md, section++, target, applicationName, developerName);
        if (on.contains(BriefSection.CATEGORY_REVIEW)) categoryReview(md, section++, byCategory, sorted.size());
        requirementBody(md, section++, byCategory, on.contains(BriefSection.ACCEPTANCE_CRITERIA));
        if (on.contains(BriefSection.OPEN_QUESTIONS)) openQuestions(md, section++, sorted);
        if (on.contains(BriefSection.DEFINITION_OF_DONE)) {
            definitionOfDone(md, section++, target, developerName, on.contains(BriefSection.COMMIT_TRAILER));
        }
        if (on.contains(BriefSection.QUALITY_APPENDIX)) qualityAppendix(md, section, sorted);
        return md.toString();
    }

    private static void header(StringBuilder md, String applicationName, List<String> capabilityNames,
                                 BriefTarget target, String developerName, Instant asOf,
                                 int included, Map<String, Long> excluded, long excludedNoTestCase) {
        md.append("# Implementation brief — ").append(applicationName).append("\n\n");
        md.append("- **Target:** ").append(target).append("\n");
        md.append("- **Developer:** ").append(developerName).append("\n"); // VYB-0452 AC2: header
        md.append("- **Capabilities in scope:** ")
          .append(capabilityNames.isEmpty() ? "(all)" : String.join(", ", capabilityNames)).append("\n");
        md.append("- **Generated:** ").append(asOf).append("\n");
        // VYB-0831: "approved" alone no longer means "in this brief" — a requirement also
        // needs a test case, so the header says so plainly rather than letting "approved"
        // read as the whole gate the way it used to.
        md.append("- **Requirements:** ").append(included).append(" approved");
        long statusExcludedTotal = excluded.values().stream().mapToLong(Long::longValue).sum();
        long totalExcluded = statusExcludedTotal + excludedNoTestCase;
        if (totalExcluded > 0) {
            md.append(" (").append(totalExcluded).append(" excluded — ");
            List<String> parts = new ArrayList<>();
            if (statusExcludedTotal > 0) {
                parts.add(excluded.entrySet().stream()
                    .map(e -> e.getValue() + " " + e.getKey().toLowerCase().replace('_', ' '))
                    .reduce((a, b) -> a + ", " + b).orElse(""));
            }
            if (excludedNoTestCase > 0) {
                parts.add(excludedNoTestCase + " approved with no test case yet");
            }
            md.append(String.join(", ", parts));
            md.append(". Only approved requirements with a test case are briefed.)");
        }
        md.append("\n\n");
    }

    /** VYB-0451 AC3: rewritten per target, not just relabelled. */
    private static void contextSection(StringBuilder md, int n, BriefTarget target, String applicationName, String developerName) {
        md.append("## ").append(n).append(". Context\n\n");
        switch (target) {
            case CLAUDE_CODE -> md.append(
                "You are implementing the requirements below against **" + applicationName + "**, assigned to "
                + developerName + ". Match the existing codebase's conventions rather than introducing new ones. "
                + "Write a test for every acceptance criterion before the code that satisfies it. Every commit "
                + "that implements one of these requirements must carry a `Requirement: KEY` trailer (see §3) — "
                + "that trailer is what links your commit back to the requirement in Vyoog; a commit with no "
                + "trailer is flagged as untraced.\n\n");
            case CURSOR -> md.append(
                "Implementation task for **" + applicationName + "**, assigned to " + developerName + ". "
                + "Work through the requirements below in order. Before you change anything, read the project's "
                + "rules file if one exists and follow it over any habit of your own — this codebase's conventions "
                + "win over general ones. Write the test for an acceptance criterion before the code that makes it "
                + "pass. Keep each requirement's change reviewable on its own rather than one sweeping edit, and "
                + "carry a `Requirement: KEY` trailer on every commit (§3) so Vyoog can trace the change back "
                + "to what asked for it.\n\n");
            case CODEX -> md.append(
                "Implementation task for **" + applicationName + "**, assigned to " + developerName + ". "
                + "Follow the existing code style in this repository. Each acceptance criterion below should be "
                + "verifiable by a test. Commits implementing these requirements must include a `Requirement: KEY` "
                + "trailer in the commit message (§3) so Vyoog can trace the change back to its requirement.\n\n");
            case HUMAN -> md.append(
                "Hi " + developerName + " — this brief covers the requirements below for " + applicationName
                + ". Read each one's acceptance criteria before you start; they're what a test will eventually "
                + "check against. When you're done, make sure the definition of done at the bottom is actually "
                + "true before you call it finished.\n\n");
        }
    }

    /** VYB-0453: counted by category, non-functional's absence called out, test coverage stated. */
    private static void categoryReview(StringBuilder md, int n, Map<String, List<BriefRequirementView>> byCategory, int total) {
        md.append("## ").append(n).append(". Category review\n\n");
        for (String cat : CATEGORY_ORDER) {
            int count = byCategory.get(cat).size();
            md.append("- ").append(cat).append(": ").append(count);
            if (cat.equals("Non-functional") && count == 0) {
                md.append(" — **none in scope.** Confirm that's deliberate, not an omission.");
            }
            md.append("\n");
        }
        long withTest = byCategory.values().stream().flatMap(List::stream).filter(BriefRequirementView::hasTest).count();
        md.append("\n**Test coverage:** ").append(withTest).append(" of ").append(total)
          .append(" requirements in scope have a passing test at their current revision.\n\n");
    }

    /** VYB-0454: grouped by category, each group separately numbered. */
    private static void requirementBody(StringBuilder md, int n, Map<String, List<BriefRequirementView>> byCategory, boolean withCriteria) {
        md.append("## ").append(n).append(". Requirements\n\n");
        int groupNum = 0;
        for (String cat : CATEGORY_ORDER) {
            List<BriefRequirementView> group = byCategory.get(cat);
            if (group.isEmpty()) continue;
            groupNum++;
            md.append("### ").append(n).append(".").append(groupNum).append(" ").append(cat).append("\n\n");
            int itemNum = 0;
            for (BriefRequirementView r : group) {
                itemNum++;
                md.append(groupNum).append(".").append(itemNum).append(". **").append(r.key())
                  .append("** — ").append(r.title()).append(" (rev ").append(r.revision()).append(")\n\n");
                md.append("   ").append(r.statement()).append("\n\n");
                // D12/Principle 8: a rule inherited from the application or the product is
                // marked as such. Rendering it identically to a capability requirement
                // would read as though somebody wrote it for this work specifically, and
                // an agent has no way to tell that changing it affects everything else.
                if (r.isInherited()) {
                    md.append("   *Applies to ").append(r.scopeLabel())
                      .append(" — not written for this capability. Satisfy it; do not redefine it.*\n\n");
                }
                if (r.dueOn() != null) {
                    md.append("   *Due:* ").append(r.dueOn()).append("\n\n");
                }
                if (r.rationale() != null && !r.rationale().isBlank()) {
                    md.append("   *Rationale:* ").append(r.rationale()).append("\n\n");
                }
                // VYB-0817: alongside the statement above, never in place of it — the
                // statement is what a human wrote and approved; this is a requested,
                // clearly-labelled expansion of it, so a reader can never mistake one
                // for the other.
                if (r.aiElaboration() != null && !r.aiElaboration().isBlank()) {
                    md.append("   *AI elaboration (expands on the statement above; not itself authoritative):*\n\n");
                    md.append("   ").append(r.aiElaboration()).append("\n\n");
                }
                // VYB-0831: always shown, unlike acceptance criteria below — a test case is
                // why this requirement is in the brief at all now, not an optional section a
                // caller can switch off the way ACCEPTANCE_CRITERIA can.
                testCasesBlock(md, r);
                if (!withCriteria) {
                    continue; // criteria deliberately excluded — say nothing rather than claim there are none
                }
                if (r.criteria().isEmpty()) {
                    md.append("   *No acceptance criteria recorded.*\n\n");
                } else {
                    md.append("   Acceptance criteria:\n");
                    for (String c : r.criteria()) md.append("   - ").append(c).append("\n");
                    md.append("\n");
                }
            }
        }
    }

    /**
     * VYB-0831: this requirement's own test cases, inline right under it — the reason
     * it's in this brief at all now. Each test case's own description is rendered as
     * sub-bullets rather than one run-on line, stripping any "-"/"•" marker it already
     * carries (most are AI-generated bulleted steps) so a description that is already a
     * bulleted list doesn't end up double-marked.
     */
    private static void testCasesBlock(StringBuilder md, BriefRequirementView r) {
        if (r.testCases().isEmpty()) return;
        md.append("   Test cases:\n\n");
        for (BriefTestCase tc : r.testCases()) {
            String category = tc.category() == null ? "" : " (" + tc.category().toLowerCase() + ")";
            md.append("   - **").append(tc.title()).append("**").append(category).append("\n");
            if (tc.description() != null && !tc.description().isBlank()) {
                for (String line : tc.description().split("\n")) {
                    String trimmed = line.strip().replaceFirst("^[-•]\\s*", "");
                    if (!trimmed.isEmpty()) md.append("     - ").append(trimmed).append("\n");
                }
            }
        }
        md.append("\n");
    }

    /**
     * The requirements §2 could only mark as having no criteria, gathered so they read as
     * questions to ask rather than gaps to fill in silently. Nothing here is generated —
     * every entry is a requirement the register genuinely has nothing testable for.
     */
    private static void openQuestions(StringBuilder md, int n, List<BriefRequirementView> all) {
        List<BriefRequirementView> unanswered = all.stream().filter(r -> r.criteria().isEmpty()).toList();
        md.append("## ").append(n).append(". Open questions\n\n");
        if (unanswered.isEmpty()) {
            md.append("Every requirement in scope carries at least one acceptance criterion. ")
              .append("Nothing here needs asking before you start.\n\n");
            return;
        }
        md.append("The following ").append(unanswered.size()).append(" requirement")
          .append(unanswered.size() == 1 ? " has" : "s have")
          .append(" no acceptance criteria recorded. Ask what \"done\" means for each one before implementing it. ")
          .append("Do not invent a criterion and build against your own guess.\n\n");
        for (BriefRequirementView r : unanswered) {
            md.append("- **").append(r.key()).append("** — ").append(r.title()).append("\n");
        }
        md.append("\n");
    }

    /**
     * Vyoog's own quality score per requirement, so the agent can tell which parts of the
     * brief rest on firmer ground. A requirement with no score is listed as unscored
     * rather than defaulted to a number nobody computed.
     */
    private static void qualityAppendix(StringBuilder md, int n, List<BriefRequirementView> all) {
        md.append("## ").append(n).append(". Quality appendix\n\n");
        md.append("Vyoog scores each requirement on its own wording and coverage. ")
          .append("A low score means the requirement is weak, not that the feature is hard.\n\n");
        md.append("| Requirement | Score |\n|---|---|\n");
        for (BriefRequirementView r : all) {
            md.append("| ").append(r.key()).append(" | ")
              .append(r.qualityScore() == null ? "not scored" : String.valueOf(r.qualityScore()))
              .append(" |\n");
        }
        md.append("\n");
    }

    /** VYB-0457: covers tests, trailers and the register update, and names the developer (AC1). */
    private static void definitionOfDone(StringBuilder md, int n, BriefTarget target, String developerName, boolean withTrailer) {
        md.append("## ").append(n).append(". Definition of done\n\n");
        md.append("- Every acceptance criterion above has a passing test.\n");
        if (withTrailer && target != BriefTarget.HUMAN) {
            // VYB-0451 AC1: the human target omits the commit-trailer block entirely, whatever the caller asked for.
            md.append("- Every commit implementing one of these requirements carries a `Requirement: KEY` trailer.\n");
        }
        md.append("- The requirement's status in Vyoog reflects what was actually done — move it to In review, ")
          .append("not left in Draft, once ").append(developerName).append(" is satisfied it's complete.\n");
        if (target == BriefTarget.CLAUDE_CODE) {
            // VYB-0451 AC2: test-naming convention appears only for Claude Code.
            md.append("- Test names follow this codebase's own convention: a full sentence describing the ")
              .append("behaviour under test, not `test1`/`shouldWork`.\n");
        }
        if (target == BriefTarget.CURSOR) {
            // Cursor edits in place across many files at once, so the line that earns its
            // place here is the one about not touching what no requirement above asked for.
            md.append("- No file is changed that none of the requirements above called for. ")
              .append("Unrelated refactors belong in their own change, not this one.\n");
        }
        md.append("\nSigned off by: ").append(developerName).append("\n");
    }
}
