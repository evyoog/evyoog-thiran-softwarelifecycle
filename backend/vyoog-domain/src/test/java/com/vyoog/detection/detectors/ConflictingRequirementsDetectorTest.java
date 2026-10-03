package com.vyoog.detection.detectors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.vyoog.ai.AiUsageTracker;
import com.vyoog.ai.LlmAdjudicator;
import com.vyoog.ai.SimilaritySearchService;
import com.vyoog.ai.AiProviderUnavailableException;
import com.vyoog.detection.DetectorNotConfiguredException;
import com.vyoog.detection.DetectorUnavailableException;
import com.vyoog.detection.GapRuleService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** VYB-0612 AC1: no finding is ever created without an actual adjudication. */
@ExtendWith(MockitoExtension.class)
class ConflictingRequirementsDetectorTest {

    @Mock SimilaritySearchService similarity;
    @Mock GapRuleService gapRules;
    @Mock AiUsageTracker aiUsage;
    @Mock LlmAdjudicator adjudicator;

    @BeforeEach
    void adjudicatorIsConfigured() {
        lenient().when(adjudicator.isConfigured()).thenReturn(true); // a mock's default would be false
    }

    private SimilaritySearchService.SimilarPair pair() {
        return new SimilaritySearchService.SimilarPair(
            UUID.randomUUID(), "VY-1", "The system shall allow logins.",
            UUID.randomUUID(), "VY-2", "The system shall reject all logins.");
    }

    @Test
    void withNoAdjudicatorConfiguredScanThrowsRatherThanReturningNoFindings() {
        var detector = new ConflictingRequirementsDetector(similarity, Optional.empty(), gapRules, aiUsage);

        // Unavailable, not "ran and found nothing" — DetectionSweepService treats these differently.
        assertThatThrownBy(detector::scan).isInstanceOf(DetectorUnavailableException.class);
    }

    @Test
    void aNonContradictingVerdictProducesNoFinding() {
        when(similarity.allPairsAboveThreshold(anyDouble(), anyInt())).thenReturn(List.of(pair()));
        when(aiUsage.tryConsume()).thenReturn(true);
        when(adjudicator.adjudicate(any(), any()))
            .thenReturn(new LlmAdjudicator.Verdict(false, "They don't actually overlap", 0.9));
        var detector = new ConflictingRequirementsDetector(similarity, Optional.of(adjudicator), gapRules, aiUsage);

        assertThat(detector.scan()).isEmpty();
    }

    @Test
    void aContradictingVerdictProducesAFindingCarryingProvenance() {
        when(similarity.allPairsAboveThreshold(anyDouble(), anyInt())).thenReturn(List.of(pair()));
        when(aiUsage.tryConsume()).thenReturn(true);
        when(adjudicator.adjudicate(any(), any()))
            .thenReturn(new LlmAdjudicator.Verdict(true, "One allows what the other forbids", 0.82));
        when(adjudicator.modelAndPromptVersion()).thenReturn("test-model@prompt-v1");
        var detector = new ConflictingRequirementsDetector(similarity, Optional.of(adjudicator), gapRules, aiUsage);

        var findings = detector.scan();

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).confidence()).isEqualTo(0.82);
        assertThat(findings.get(0).model()).isEqualTo("test-model@prompt-v1");
        assertThat(findings.get(0).detail()).contains("One allows what the other forbids");
    }

    @Test
    void exhaustingTheAiBudgetDefersRemainingPairsRatherThanFailing() {
        when(similarity.allPairsAboveThreshold(anyDouble(), anyInt())).thenReturn(List.of(pair(), pair()));
        when(aiUsage.tryConsume()).thenReturn(false); // budget already spent
        var detector = new ConflictingRequirementsDetector(similarity, Optional.of(adjudicator), gapRules, aiUsage);

        assertThat(detector.scan()).isEmpty();
        verify(adjudicator, never()).adjudicate(any(), any());
    }

    @Test
    void VYB0911_AC3_anAdjudicatorThatIsSwitchedOffIsReportedNotConfiguredBeforeAnythingIsQueriedOrSpent() {
        when(adjudicator.isConfigured()).thenReturn(false);
        var detector = new ConflictingRequirementsDetector(similarity, Optional.of(adjudicator), gapRules, aiUsage);

        assertThatThrownBy(detector::scan).isInstanceOf(DetectorNotConfiguredException.class);
        assertThatThrownBy(() -> detector.scanOne(UUID.randomUUID())).isInstanceOf(DetectorNotConfiguredException.class);
        verifyNoInteractions(similarity, aiUsage);
        verify(adjudicator, never()).adjudicate(any(), any());
    }

    @Test
    void VYB0911_AC3_aConfiguredProviderThatIsDownIsReportedUnavailableNotAsAnUnexpectedFailure() {
        when(similarity.allPairsAboveThreshold(anyDouble(), anyInt())).thenReturn(List.of(pair()));
        when(aiUsage.tryConsume()).thenReturn(true);
        when(adjudicator.adjudicate(any(), any())).thenThrow(new AiProviderUnavailableException("timed out"));
        var detector = new ConflictingRequirementsDetector(similarity, Optional.of(adjudicator), gapRules, aiUsage);

        assertThatThrownBy(detector::scan)
            .isInstanceOf(DetectorUnavailableException.class)
            .isNotInstanceOf(DetectorNotConfiguredException.class)
            .hasMessageContaining("timed out");
    }
}
