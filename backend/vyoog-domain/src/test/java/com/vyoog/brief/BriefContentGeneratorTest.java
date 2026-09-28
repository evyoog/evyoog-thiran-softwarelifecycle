package com.vyoog.brief;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure-function tests — no mocks needed, {@link BriefContentGenerator} takes only plain data. */
class BriefContentGeneratorTest {

    private static final Instant ASOF = Instant.parse("2026-01-01T00:00:00Z");

    private static BriefRequirementView req(String key, String type, boolean hasTest, String... criteria) {
        return new BriefRequirementView(key, key + " title", key + " statement", null, type, 1, hasTest, List.of(criteria));
    }

    @Test
    void sameInputProducesByteIdenticalOutput() {
        List<BriefRequirementView> reqs = List.of(req("VY-2", "FUNCTIONAL", true), req("VY-1", "FUNCTIONAL", false));
        String a = BriefContentGenerator.generate("App", List.of("Cap"), reqs, BriefTarget.HUMAN, "Dev", ASOF);
        String b = BriefContentGenerator.generate("App", List.of("Cap"), reqs, BriefTarget.HUMAN, "Dev", ASOF);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void requirementsAreSortedByKeyRegardlessOfInputOrder() {
        List<BriefRequirementView> reqs = List.of(req("VY-9", "FUNCTIONAL", true), req("VY-2", "FUNCTIONAL", true));
        String md = BriefContentGenerator.generate("App", List.of(), reqs, BriefTarget.HUMAN, "Dev", ASOF);
        assertThat(md.indexOf("VY-2")).isLessThan(md.indexOf("VY-9"));
    }

    @Test
    void humanTargetOmitsTheCommitTrailerBlock() {
        String md = BriefContentGenerator.generate("App", List.of(), List.of(req("VY-1", "FUNCTIONAL", true)),
            BriefTarget.HUMAN, "Dev", ASOF);
        assertThat(md).doesNotContain("Requirement: KEY");
    }

    @Test
    void VYB0817_elaborationRendersAlongsideTheStatementNotInPlaceOfIt() {
        BriefRequirementView withElaboration = req("VY-1", "FUNCTIONAL", true)
            .withAiElaboration("This expands on what VY-1 statement already says.");
        String md = BriefContentGenerator.generate("App", List.of(), List.of(withElaboration), BriefTarget.HUMAN, "Dev", ASOF);

        assertThat(md).contains("VY-1 statement"); // the original statement is still there
        assertThat(md).contains("AI elaboration");
        assertThat(md).contains("This expands on what VY-1 statement already says.");
    }

    @Test
    void VYB0817_noElaborationMeansNoElaborationSectionAtAll() {
        String md = BriefContentGenerator.generate("App", List.of(), List.of(req("VY-1", "FUNCTIONAL", true)),
            BriefTarget.HUMAN, "Dev", ASOF);
        assertThat(md).doesNotContain("AI elaboration");
    }

    @Test
    void claudeCodeAndCodexIncludeTheCommitTrailerBlock() {
        for (BriefTarget target : List.of(BriefTarget.CLAUDE_CODE, BriefTarget.CODEX, BriefTarget.CURSOR)) {
            String md = BriefContentGenerator.generate("App", List.of(), List.of(req("VY-1", "FUNCTIONAL", true)),
                target, "Dev", ASOF);
            assertThat(md).contains("Requirement: KEY");
        }
    }

    /**
     * The check that actually protects a new target: every value the enum declares has to
     * render. A branch missing from the §0 switch would otherwise throw at generation time
     * for that target alone — invisible until someone selected it.
     */
    @Test
    void everyDeclaredTargetRendersAFullBrief() {
        for (BriefTarget target : BriefTarget.values()) {
            String md = BriefContentGenerator.generate("App", List.of("Cap"),
                List.of(req("VY-1", "FUNCTIONAL", true)), target, "Dev", ASOF);
            assertThat(md).as("%s", target)
                .contains("## 0.").contains("## 1.").contains("## 2.").contains("## 3.")
                .contains("VY-1").contains("Dev");
        }
    }

    @Test
    void cursorGetsItsOwnContextAndItsOwnDefinitionOfDoneLine() {
        String cursor = BriefContentGenerator.generate("App", List.of(),
            List.of(req("VY-1", "FUNCTIONAL", true)), BriefTarget.CURSOR, "Dev", ASOF);
        String claude = BriefContentGenerator.generate("App", List.of(),
            List.of(req("VY-1", "FUNCTIONAL", true)), BriefTarget.CLAUDE_CODE, "Dev", ASOF);

        // VYB-0451 AC3: rewritten per target, not relabelled — the two must not be the same prose.
        assertThat(section0(cursor)).isNotEqualTo(section0(claude));
        assertThat(cursor).contains("rules file");
        assertThat(cursor).contains("No file is changed that none of the requirements above called for");
        // The scope-discipline line is Cursor's alone, and the test-naming line is not Cursor's.
        assertThat(claude).doesNotContain("No file is changed that none of the requirements above called for");
        assertThat(cursor).doesNotContain("Test names follow this codebase's own convention");
    }

    private static String section0(String md) {
        return md.substring(md.indexOf("## 0."), md.indexOf("## 1."));
    }

    @Test
    void testNamingConventionAppearsOnlyForClaudeCode() {
        String claude = BriefContentGenerator.generate("App", List.of(), List.of(req("VY-1", "FUNCTIONAL", true)),
            BriefTarget.CLAUDE_CODE, "Dev", ASOF);
        String codex = BriefContentGenerator.generate("App", List.of(), List.of(req("VY-1", "FUNCTIONAL", true)),
            BriefTarget.CODEX, "Dev", ASOF);
        String human = BriefContentGenerator.generate("App", List.of(), List.of(req("VY-1", "FUNCTIONAL", true)),
            BriefTarget.HUMAN, "Dev", ASOF);
        assertThat(claude).contains("Test names follow this codebase's own convention");
        assertThat(codex).doesNotContain("Test names follow this codebase's own convention");
        assertThat(human).doesNotContain("Test names follow this codebase's own convention");
    }

    @Test
    void sectionZeroDiffersByTargetNotJustLabel() {
        String claude = BriefContentGenerator.generate("App", List.of(), List.of(), BriefTarget.CLAUDE_CODE, "Dev", ASOF);
        String human = BriefContentGenerator.generate("App", List.of(), List.of(), BriefTarget.HUMAN, "Dev", ASOF);
        String claudeContext = claude.substring(claude.indexOf("## 0."), claude.indexOf("## 1."));
        String humanContext = human.substring(human.indexOf("## 0."), human.indexOf("## 1."));
        assertThat(claudeContext).isNotEqualTo(humanContext);
        assertThat(claudeContext).contains("trailer");
        assertThat(humanContext).doesNotContain("trailer");
    }

    @Test
    void absentNonFunctionalIsCalledOutExplicitly() {
        String md = BriefContentGenerator.generate("App", List.of(), List.of(req("VY-1", "FUNCTIONAL", true)),
            BriefTarget.HUMAN, "Dev", ASOF);
        assertThat(md).contains("Non-functional: 0 — **none in scope.**");
    }

    @Test
    void presentNonFunctionalIsNotFlagged() {
        String md = BriefContentGenerator.generate("App", List.of(),
            List.of(req("VY-1", "NON_FUNCTIONAL", true)), BriefTarget.HUMAN, "Dev", ASOF);
        assertThat(md).doesNotContain("none in scope");
    }

    @Test
    void categoriesAreGroupedAndSeparatelyNumbered() {
        List<BriefRequirementView> reqs = List.of(
            req("VY-1", "FUNCTIONAL", true), req("VY-2", "BUSINESS_RULE", true), req("VY-3", "SECURITY", true));
        String md = BriefContentGenerator.generate("App", List.of(), reqs, BriefTarget.HUMAN, "Dev", ASOF);
        assertThat(md).contains("### 2.1 Functional").contains("### 2.2 Business rule").contains("### 2.3 Other");
    }

    @Test
    void testCoverageIsStatedAsAnExactFraction() {
        List<BriefRequirementView> reqs = List.of(
            req("VY-1", "FUNCTIONAL", true), req("VY-2", "FUNCTIONAL", false));
        String md = BriefContentGenerator.generate("App", List.of(), reqs, BriefTarget.HUMAN, "Dev", ASOF);
        assertThat(md).contains("1 of 2 requirements in scope have a passing test");
    }

    @Test
    void definitionOfDoneNamesTheDeveloper() {
        String md = BriefContentGenerator.generate("App", List.of(), List.of(), BriefTarget.HUMAN, "Ada Lovelace", ASOF);
        assertThat(md).contains("Signed off by: Ada Lovelace");
    }

    @Test
    void VYB0831_AC3_testCasesRenderInlineUnderTheirOwnRequirementWithStepsAsSubBullets() {
        BriefRequirementView withTests = req("VY-1", "FUNCTIONAL", true).withTestCases(List.of(
            new BriefTestCase("TC-1", "Rejects a duplicate lead", "- Submit the same lead twice\n- Expect a rejection", "DEPENDENCY"),
            new BriefTestCase("TC-2", "Stores a lead with its source", null, "INDIVIDUAL")));
        String md = BriefContentGenerator.generate("App", List.of(), List.of(withTests), BriefTarget.HUMAN, "Dev", ASOF);

        assertThat(md).contains("Test cases:")
            .contains("**Rejects a duplicate lead** (dependency)")
            .contains("- Submit the same lead twice").contains("- Expect a rejection")
            .contains("**Stores a lead with its source** (individual)");
        // No leading "- -" double-marking a description that was already bulleted.
        assertThat(md).doesNotContain("- - Submit");
    }

    @Test
    void VYB0831_AC3_noTestCasesMeansNoTestCasesBlockAtAll() {
        String md = BriefContentGenerator.generate("App", List.of(), List.of(req("VY-1", "FUNCTIONAL", true)),
            BriefTarget.HUMAN, "Dev", ASOF);
        assertThat(md).doesNotContain("Test cases:");
    }

    @Test
    void VYB0831_AC4_headerNamesRequirementsExcludedForHavingNoTestCase() {
        String md = BriefContentGenerator.generate("App", List.of("Cap"), List.of(req("VY-1", "FUNCTIONAL", true)),
            BriefTarget.HUMAN, "Dev", ASOF, BriefSection.ALL, java.util.Map.of(), 3L);
        assertThat(md).contains("**Requirements:** 1 approved")
            .contains("3 excluded").contains("3 approved with no test case yet")
            .contains("Only approved requirements with a test case are briefed");
    }

    @Test
    void VYB0831_AC4_bothStatusAndNoTestCaseExclusionsAreNamedTogether() {
        String md = BriefContentGenerator.generate("App", List.of("Cap"), List.of(req("VY-1", "FUNCTIONAL", true)),
            BriefTarget.HUMAN, "Dev", ASOF, BriefSection.ALL,
            new java.util.TreeMap<>(java.util.Map.of("DRAFT", 5L)), 2L);
        assertThat(md).contains("7 excluded").contains("5 draft").contains("2 approved with no test case yet");
    }
}
