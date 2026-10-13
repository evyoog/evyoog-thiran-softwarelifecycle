package com.vyoog.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** VYB-0956 (F24, D43): the week arithmetic of the trends and the small text rules of the report, with no database. */
class TrendServiceTest {

    @Test
    void VYB0956_AC1_weeksRunMondayToSundayAndEndWithTheWeekHoldingToday() {
        LocalDate sunday = LocalDate.of(2026, 10, 18);      // a Sunday: still the week that began Monday 12 October
        var w = TrendService.weeks(sunday, 30);
        assertThat(w).hasSize(5);
        assertThat(w.get(4)).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(w.get(0)).isEqualTo(LocalDate.of(2026, 9, 14));
        assertThat(w).allSatisfy(d -> assertThat(d.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY));
        assertThat(w).isSorted();
        assertThat(TrendService.weeks(LocalDate.of(2026, 10, 19), 30).get(4)).as("the next day is a Monday and starts a new week").isEqualTo(LocalDate.of(2026, 10, 19));
    }

    @Test
    void VYB0956_AC1_thePeriodsGiveWholeWeeksAndOnlyThreeAreAllowed() {
        LocalDate day = LocalDate.of(2026, 10, 13);
        assertThat(TrendService.weeks(day, 30)).hasSize(5);
        assertThat(TrendService.weeks(day, 90)).hasSize(13);
        assertThat(TrendService.weeks(day, 180)).hasSize(26);
        assertThat(TrendService.PERIODS).containsExactlyInAnyOrder(30, 90, 180);
    }

    @Test
    void VYB0956_AC10_aMissingRateIsSaidInWordsNeverShownAsZero() {
        assertThat(TrendReportWriter.percent(null)).isEqualTo("no results");
        assertThat(TrendReportWriter.percent(0.0)).isEqualTo("0%");
        assertThat(TrendReportWriter.percent(0.755)).isEqualTo("76%");
    }

    @Test
    void VYB0956_AC11_theBuiltInPdfFontKeepsLatinOneAndMarksAnythingElse() {
        assertThat(TrendReportWriter.latin1("Café R-4")).isEqualTo("Café R-4");
        assertThat(TrendReportWriter.latin1("Release 発")).isEqualTo("Release ?");
    }
}
