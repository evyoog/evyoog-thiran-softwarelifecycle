package com.vyoog.risk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** VYB-0949 (D40): the scale and its bands, the one definition the register and the requirement page both read. */
class RiskScaleTest {

    @Test
    void VYB0949_AC13_theBandsStartWhereTheDecisionSays() {
        assertThat(java.util.stream.IntStream.of(1, 4, 5, 9, 10, 14, 15, 25).mapToObj(RiskScale::band).toList())
            .containsExactly("LOW", "LOW", "MEDIUM", "MEDIUM", "HIGH", "HIGH", "CRITICAL", "CRITICAL");
    }

    @Test
    void VYB0949_AC13_theRatingIsLikelihoodTimesImpactAndEveryCellOfTheGridHasABand() {
        assertThat(RiskScale.rating(4, 5)).isEqualTo(20);
        for (int l = RiskScale.MIN; l <= RiskScale.MAX; l++) {
            for (int i = RiskScale.MIN; i <= RiskScale.MAX; i++) {
                assertThat(RiskScale.band(RiskScale.rating(l, i))).isIn("LOW", "MEDIUM", "HIGH", "CRITICAL");
            }
        }
        assertThat(RiskScale.band(RiskScale.rating(1, 1))).isEqualTo("LOW");
        assertThat(RiskScale.band(RiskScale.rating(5, 5))).isEqualTo("CRITICAL");
    }

    @Test
    void VYB0949_AC13_aLevelIsOneToFive() {
        assertThat(java.util.stream.IntStream.of(0, 1, 5, 6, -1).mapToObj(RiskScale::valid).toList()).containsExactly(false, true, true, false, false);
    }
}
