package com.vyoog.brief;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * VYB-0451/0454: the Include toggles on the brief generator. What matters here is that a
 * cleared box actually removes content and, just as importantly, that the sections which
 * survive stay coherent — a brief that skips from §0 to §2 sends the reader looking for a
 * section nobody wrote.
 */
class BriefSectionTest {

    private static final List<BriefRequirementView> TWO = List.of(
        new BriefRequirementView("VY-1", "Lead capture", "The system shall capture a lead.", null,
            "FUNCTIONAL", 1, false, List.of("A lead is stored with its source."), (short) 91),
        new BriefRequirementView("VY-2", "Lead dedup", "The system shall reject a duplicate lead.", null,
            "BUSINESS_RULE", 2, false, List.of(), (short) 44));

    private String render(Set<BriefSection> sections) {
        return BriefContentGenerator.generate("Sales", List.of("Lead Management"), TWO,
            BriefTarget.CLAUDE_CODE, "Sibi", Instant.EPOCH, sections);
    }

    @Test
    void VYB0451_AC3_everySectionByDefaultAndAnEmptySetMeansTheSameThing() {
        assertThat(render(BriefSection.ALL)).isEqualTo(render(Set.of())).isEqualTo(render(null));
        String md = render(BriefSection.ALL);
        assertThat(md).contains("Context").contains("Category review").contains("Requirements")
            .contains("Open questions").contains("Definition of done").contains("Quality appendix");
    }

    @Test
    void VYB0454_AC1_aClearedSectionIsActuallyAbsentNotJustEmptied() {
        String md = render(Set.of(BriefSection.DEFINITION_OF_DONE));

        assertThat(md).doesNotContain("Context").doesNotContain("Category review")
            .doesNotContain("Quality appendix").doesNotContain("Open questions");
        // The parts that are never optional survive regardless.
        assertThat(md).contains("# Implementation brief — Sales")
            .contains("**Developer:** Sibi")
            .contains("VY-1")
            .contains("Definition of done");
    }

    @Test
    void VYB0454_AC1_sectionsAreNumberedAsEmittedSoNoNumberIsSkipped() {
        String md = render(Set.of(BriefSection.CONTEXT, BriefSection.DEFINITION_OF_DONE));

        // Context 0, Requirements 1, Definition of done 2 — no gap where the category
        // review would have been.
        assertThat(md).contains("## 0. Context").contains("## 1. Requirements").contains("## 2. Definition of done");
        assertThat(md).doesNotContain("## 3.");
    }

    @Test
    void VYB0454_AC1_droppingCriteriaSaysNothingRatherThanClaimingThereAreNone() {
        String md = render(Set.of(BriefSection.ACCEPTANCE_CRITERIA));
        assertThat(md).contains("A lead is stored with its source.")
            .contains("*No acceptance criteria recorded.*"); // VY-2 genuinely has none

        String without = render(Set.of(BriefSection.CONTEXT));
        assertThat(without).doesNotContain("A lead is stored with its source.")
            .doesNotContain("No acceptance criteria recorded"); // silence, not a false claim
    }

    @Test
    void VYB0454_AC1_openQuestionsListsOnlyRequirementsThatGenuinelyHaveNoCriteria() {
        String md = render(Set.of(BriefSection.OPEN_QUESTIONS));

        assertThat(md).contains("Open questions").contains("VY-2").contains("Lead dedup");
        assertThat(md).contains("Do not invent a criterion");
        // VY-1 has a criterion, so it is not a question to ask.
        assertThat(md.substring(md.indexOf("Open questions"))).doesNotContain("VY-1");
    }

    @Test
    void VYB0454_AC1_openQuestionsSaysSoWhenThereAreNoneRatherThanRenderingAnEmptyHeading() {
        String md = BriefContentGenerator.generate("Sales", List.of(), List.of(TWO.get(0)),
            BriefTarget.CLAUDE_CODE, "Sibi", Instant.EPOCH, Set.of(BriefSection.OPEN_QUESTIONS));

        assertThat(md).contains("Nothing here needs asking before you start.");
    }

    @Test
    void VYB0454_AC1_theQualityAppendixReportsAnUnscoredRequirementAsUnscored() {
        List<BriefRequirementView> unscored = List.of(new BriefRequirementView(
            "VY-9", "No score", "The system shall do a thing.", null, "FUNCTIONAL", 1, false, List.of()));
        String md = BriefContentGenerator.generate("Sales", List.of(), unscored,
            BriefTarget.CLAUDE_CODE, "Sibi", Instant.EPOCH, Set.of(BriefSection.QUALITY_APPENDIX));

        // Principle 8: absent is stated, never defaulted to a number nobody computed.
        assertThat(md).contains("| VY-9 | not scored |");
        assertThat(render(BriefSection.ALL)).contains("| VY-1 | 91 |").contains("| VY-2 | 44 |");
    }

    @Test
    void VYB0455_AC1_theHeaderNamesWhatWasExcludedForNotBeingApproved() {
        String md = BriefContentGenerator.generate("Sales", List.of("Lead Management"), TWO,
            BriefTarget.CLAUDE_CODE, "Sibi", Instant.EPOCH, BriefSection.ALL,
            new java.util.TreeMap<>(java.util.Map.of("DRAFT", 13L, "REJECTED", 2L)));

        // Principle 8: a two-requirement brief drawn from seventeen says so.
        assertThat(md).contains("**Requirements:** 2 approved")
            .contains("15 excluded").contains("13 draft").contains("2 rejected")
            .contains("Only approved requirements with a test case are briefed");
    }

    @Test
    void VYB0455_AC1_nothingExcludedMeansNoParentheticalAtAll() {
        String md = render(BriefSection.ALL);
        assertThat(md).contains("**Requirements:** 2 approved").doesNotContain("excluded");
    }

    @Test
    void VYB0455_AC1_aBriefWithNoApprovedRequirementsIsRefusedRatherThanGeneratedEmpty() {
        // The reported bug: it generated, and handed back a header plus a definition of
        // done for nothing. A file that looks like a brief and carries no requirements is
        // worse than a refusal, because the reader has to notice the absence themselves.
        assertThat(BriefContentGenerator.generate("Sales", List.of("Lead Management"), List.of(),
            BriefTarget.CLAUDE_CODE, "Sibi", Instant.EPOCH, BriefSection.ALL,
            new java.util.TreeMap<>(java.util.Map.of("DRAFT", 16L))))
            .contains("**Requirements:** 0 approved").contains("16 excluded");
        // The generator itself still renders whatever it is handed — it is a pure
        // function. BriefService is what refuses to call it with nothing; see
        // BriefEmptyScopeTest for that guard.
    }

    @Test
    void VYB0450_AC2_requirementsAreNumberedInHumanOrderNotStringOrder() {
        List<BriefRequirementView> outOfOrder = List.of(
            new BriefRequirementView("VY-10", "Ten", "s", null, "FUNCTIONAL", 1, false, List.of()),
            new BriefRequirementView("VY-2", "Two", "s", null, "FUNCTIONAL", 1, false, List.of()),
            new BriefRequirementView("VY-1", "One", "s", null, "FUNCTIONAL", 1, false, List.of()));

        String md = BriefContentGenerator.generate("Sales", List.of(), outOfOrder,
            BriefTarget.CLAUDE_CODE, "Sibi", Instant.EPOCH);

        // String ordering gave VY-1, VY-10, VY-2 — a list that reads as though the
        // numbering means nothing.
        assertThat(md.indexOf("VY-1**")).isLessThan(md.indexOf("VY-2**"));
        assertThat(md.indexOf("VY-2**")).isLessThan(md.indexOf("VY-10**"));
    }

    @Test
    void VYB0451_AC1_theHumanTargetDropsTheTrailerEvenWhenTheCallerAsksForIt() {
        String human = BriefContentGenerator.generate("Sales", List.of(), TWO,
            BriefTarget.HUMAN, "Sibi", Instant.EPOCH,
            Set.of(BriefSection.DEFINITION_OF_DONE, BriefSection.COMMIT_TRAILER));

        assertThat(human).contains("Definition of done").doesNotContain("trailer");
    }

    @Test
    void VYB0451_AC1_theTrailerCanBeDroppedWithoutLosingTheRestOfTheDefinitionOfDone() {
        String md = render(Set.of(BriefSection.DEFINITION_OF_DONE));

        assertThat(md).contains("Every acceptance criterion above has a passing test.");
        assertThat(md).doesNotContain("trailer");
    }
}
