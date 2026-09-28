package com.vyoog.detection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AmbiguousTermLexiconTest {

    @Test
    void coversAtLeastFortyFiveTerms() {
        // VYB-0160 AC1 — see the class doc for the provenance gap against the
        // original prototype list.
        assertThat(AmbiguousTermLexicon.termCount()).isGreaterThanOrEqualTo(45);
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertThat(AmbiguousTermLexicon.findIn("The system SHALL be User-Friendly."))
            .extracting(AmbiguousTermLexicon.Match::term).containsExactly("user-friendly");
    }

    @Test
    void matchingIsWordBounded() {
        // "mostly" must not match "most"; "efficiently" must not match "efficient" —
        // neither "most" nor "efficient" appears as its own word in this sentence.
        assertThat(AmbiguousTermLexicon.findIn("This runs mostly efficiently and quite nicely."))
            .isEmpty();
    }

    @Test
    void findsMultipleDistinctTermsInOneStatement() {
        var matches = AmbiguousTermLexicon.findIn(
            "The system shall be fast and user-friendly, handling several edge cases as needed.");

        assertThat(matches).extracting(AmbiguousTermLexicon.Match::term)
            .contains("user-friendly", "several", "as needed");
    }

    @Test
    void reportsOneMatchPerTermNotPerOccurrence() {
        var matches = AmbiguousTermLexicon.findIn("It must be robust. Really, really robust.");

        assertThat(matches).hasSize(1);
    }

    @Test
    void everyMatchCarriesASuggestion() {
        for (var m : AmbiguousTermLexicon.findIn("A simple, robust and scalable design.")) {
            assertThat(m.suggestion()).isNotBlank();
        }
    }

    @Test
    void aMeasurableStatementMatchesNothing() {
        assertThat(AmbiguousTermLexicon.findIn(
            "The API shall respond within 300ms for 95% of requests under 1000 concurrent users."))
            .isEmpty();
    }
}
