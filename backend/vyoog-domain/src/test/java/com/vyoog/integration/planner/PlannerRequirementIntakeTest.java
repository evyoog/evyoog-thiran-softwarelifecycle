package com.vyoog.integration.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vyoog.integration.planner.PlannerRequirementIntake.Push;
import java.util.List;
import org.junit.jupiter.api.Test;

/** VYB-0915: the rules of the Planner intake that need no database. */
class PlannerRequirementIntakeTest {

    private static Push push(String title, String description, String priority, List<String> criteria) {
        return new Push("agile_planner", "FN-1", null, null, null, title, description, priority, criteria);
    }

    @Test
    void VYB0915_AC4_priorityMapsByNameWhateverTheCaseAndAnythingElseIsMedium() {
        assertThat(PlannerRequirementIntake.priorityOf("high")).isEqualTo("HIGH");
        assertThat(PlannerRequirementIntake.priorityOf(" Critical ")).isEqualTo("CRITICAL");
        assertThat(PlannerRequirementIntake.priorityOf("LOW")).isEqualTo("LOW");
        assertThat(PlannerRequirementIntake.priorityOf("medium")).isEqualTo("MEDIUM");
        assertThat(PlannerRequirementIntake.priorityOf("blocker")).isEqualTo("MEDIUM");
        assertThat(PlannerRequirementIntake.priorityOf("")).isEqualTo("MEDIUM");
        assertThat(PlannerRequirementIntake.priorityOf(null)).isEqualTo("MEDIUM");
    }

    @Test
    void VYB0915_AC3_theSameWordsHashAlikeWhateverTheSpacingAndPriorityCaseAndDifferentWordsDoNot() {
        String a = PlannerRequirementIntake.contentHash(push("Pause", "Pause it.", "high", List.of("one", "two")));
        assertThat(PlannerRequirementIntake.contentHash(push("  Pause ", " Pause it. ", "HIGH", List.of(" one", "", "two ")))).isEqualTo(a);
        assertThat(PlannerRequirementIntake.contentHash(push("Pause", "Pause it.", "high", List.of("two", "one")))).as("order matters").isNotEqualTo(a);
        assertThat(PlannerRequirementIntake.contentHash(push("Pause", "Pause it!", "high", List.of("one", "two")))).isNotEqualTo(a);
        assertThat(PlannerRequirementIntake.contentHash(push("Pause", "Pause it.", "low", List.of("one", "two")))).isNotEqualTo(a);
        // the criteria must not run together: ["ab"] is not ["a", "b"]
        assertThat(PlannerRequirementIntake.contentHash(push("T", "D", null, List.of("ab"))))
            .isNotEqualTo(PlannerRequirementIntake.contentHash(push("T", "D", null, List.of("a", "b"))));
    }

    @Test
    void VYB0915_AC4_aMissingDescriptionIsStatedByTheTitle() {
        assertThat(PlannerRequirementIntake.statementOf(push(" Title ", null, null, null))).isEqualTo("Title");
        assertThat(PlannerRequirementIntake.statementOf(push("Title", "  ", null, null))).isEqualTo("Title");
        assertThat(PlannerRequirementIntake.statementOf(push("Title", " Body ", null, null))).isEqualTo("Body");
    }

    @Test
    void VYB0915_AC6_validationNamesWhatIsWrong() {
        Push ok = push("Title", "Body", "high", List.of("c"));
        PlannerRequirementIntake.validate("key", ok);
        assertThatThrownBy(() -> PlannerRequirementIntake.validate(null, ok)).hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> PlannerRequirementIntake.validate(" ", ok)).hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> PlannerRequirementIntake.validate("k".repeat(201), ok)).hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> PlannerRequirementIntake.validate("has space", ok)).hasMessageContaining("printable ASCII");
        assertThatThrownBy(() -> PlannerRequirementIntake.validate("tab\there", ok)).hasMessageContaining("printable ASCII");
        assertThatThrownBy(() -> PlannerRequirementIntake.validate("caf\u00e9", ok)).hasMessageContaining("printable ASCII");
        PlannerRequirementIntake.validate("FN-123-2026-10-16T09:00:00Z", ok); // the contract's own shape: a Function id and a timestamp
        assertThatThrownBy(() -> PlannerRequirementIntake.validate("key", null)).hasMessageContaining("body");
        assertThatThrownBy(() -> PlannerRequirementIntake.validate("key",
            new Push("jira", "FN", null, null, null, "T", "B", null, null))).hasMessageContaining("source_system");
        assertThatThrownBy(() -> PlannerRequirementIntake.validate("key",
            new Push("agile_planner", null, null, null, null, "T", "B", null, null))).hasMessageContaining("source_function_id");
        assertThatThrownBy(() -> PlannerRequirementIntake.validate("key", push(null, "B", null, null))).hasMessageContaining("title");
        assertThatThrownBy(() -> PlannerRequirementIntake.validate("key", push("t".repeat(301), "B", null, null))).hasMessageContaining("title");
        assertThatThrownBy(() -> PlannerRequirementIntake.validate("key", push("T", "d".repeat(20_001), null, null))).hasMessageContaining("description");
        assertThatThrownBy(() -> PlannerRequirementIntake.validate("key", push("T", "B", null, List.of("c".repeat(2_001))))).hasMessageContaining("criterion");
    }
}
