package com.vyoog.clause;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.clause.ClauseTemplateService.Entry;
import com.vyoog.clause.ClauseTemplateService.Template;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** VYB-0950 (D41): the shipped templates are well-formed and can always be applied; a malformed one is refused when read. */
class ClauseTemplateServiceTest {

    private final ClauseTemplateService service = new ClauseTemplateService(null, null, new ObjectMapper());

    private static Template template(Entry... entries) {
        return new Template("t", "A template", "STD", "note", List.of(entries));
    }

    @Test
    void VYB0950_AC1_theIso26262TemplateIsShippedAndWellFormed() {
        var t = service.all().stream().filter(x -> x.id().equals("iso-26262")).findFirst().orElseThrow();
        assertThat(t.standard()).isEqualTo("ISO 26262");
        assertThat(t.clauses()).hasSizeGreaterThanOrEqualTo(20);
        assertThat(t.note()).contains("licensed copy").contains("never the standard's text");
        assertThat(new HashSet<>(t.clauses().stream().map(c -> c.section()).toList())).hasSameSizeAs(t.clauses());
        for (Entry e : t.clauses()) {
            assertThat(e.section()).matches("\\d{1,2}-\\d{1,2}");
            assertThat(e.title()).isNotBlank();
            assertThat(e.text()).isNotBlank().hasSizeLessThan(400);
        }
    }

    @Test
    void VYB0950_AC2_theTemplateCoversTheSoftwareProcessAndSupportingParts() {
        var parts = service.all().stream().filter(x -> x.id().equals("iso-26262")).findFirst().orElseThrow().clauses().stream()
            .map(c -> c.section().substring(0, c.section().indexOf('-'))).distinct().toList();
        assertThat(parts).containsExactly("2", "3", "4", "6", "8");     // hardware (part 5) is deliberately left out
    }

    @Test
    void VYB0950_AC3_aStoredClauseIsTheTitleThenWhatItIsFor() {
        assertThat(ClauseTemplateService.text(new Entry("6-9", " Software unit verification ", " Units are verified. "))).isEqualTo("Software unit verification. Units are verified.");
    }

    @Test
    void VYB0950_AC4_everyShippedTemplateStaysInsideTheRegistersOwnLimits() {
        for (Template t : service.all()) {
            ClauseTemplateService.validate(t, t.id());
            assertThat(t.standard().length()).isLessThanOrEqualTo(ClauseService.MAX_STANDARD);
            for (Entry e : t.clauses()) {
                assertThat(e.section().length()).isLessThanOrEqualTo(ClauseService.MAX_SECTION);
                assertThat(ClauseTemplateService.text(e).length()).isLessThanOrEqualTo(ClauseService.MAX_TEXT);
            }
        }
    }

    @Test
    void VYB0950_AC5_aMalformedTemplateIsRefusedWithItsReason() {
        var ok = new Entry("1-1", "Title", "Text");
        assertThatThrownBy(() -> ClauseTemplateService.validate(template(), "f.json")).isInstanceOf(IllegalStateException.class).hasMessageContaining("at least one clause");
        assertThatThrownBy(() -> ClauseTemplateService.validate(new Template("", "n", "S", "", List.of(ok)), "f.json")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ClauseTemplateService.validate(template(ok, new Entry("1-1", "Other", "Text")), "f.json")).hasMessageContaining("used twice");
        assertThatThrownBy(() -> ClauseTemplateService.validate(template(ok, new Entry("1-1".toUpperCase(), "Other", "Text")), "f.json")).hasMessageContaining("used twice");
        assertThatThrownBy(() -> ClauseTemplateService.validate(template(new Entry("1-2", " ", "Text")), "f.json")).hasMessageContaining("section, a title and a text");
        assertThatThrownBy(() -> ClauseTemplateService.validate(template(new Entry("x".repeat(101), "T", "Text")), "f.json")).hasMessageContaining("section too long");
        assertThatThrownBy(() -> ClauseTemplateService.validate(template(new Entry("1-3", "T", "x".repeat(4001))), "f.json")).hasMessageContaining("text too long");
        assertThatThrownBy(() -> ClauseTemplateService.validate(new Template("t", "n", "S".repeat(101), "", List.of(ok)), "f.json")).hasMessageContaining("standard is too long");
    }
}
