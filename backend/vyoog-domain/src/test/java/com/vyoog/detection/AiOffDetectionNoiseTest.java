package com.vyoog.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.ai.AiUsageTracker;
import com.vyoog.ai.OpenAiLlmAdjudicator;
import com.vyoog.ai.SimilaritySearchService;
import com.vyoog.detection.detectors.ConflictingRequirementsDetector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * VYB-0911 (F32): with AI switched off (the default), every requirement write that has a similar
 * neighbour re-ran the conflict detector, which called the always-registered OpenAI adjudicator,
 * which threw because it is not configured. That escaped as an ordinary exception, so each write
 * logged an ERROR with a full stack trace, and spent a slot of the AI-call budget on a call that
 * could never happen. Switched-off AI is a configuration, not a failure.
 *
 * <p>The adjudicator here is the real {@link OpenAiLlmAdjudicator} with nothing configured, exactly
 * as in a default deployment.
 */
class AiOffDetectionNoiseTest {

    private final SimilaritySearchService similarity = mock(SimilaritySearchService.class);
    private final AiUsageTracker aiUsage = mock(AiUsageTracker.class);
    private final GapRuleService gapRules = mock(GapRuleService.class);
    private final FindingReconciler reconciler = mock(FindingReconciler.class);
    private DetectionSweepService sweep;
    private ListAppender<ILoggingEvent> logs;
    private Logger root;

    @BeforeEach
    void setUp() {
        SimilaritySearchService.SimilarPair pair = new SimilaritySearchService.SimilarPair(
            UUID.randomUUID(), "VY-1", "The system shall allow logins.",
            UUID.randomUUID(), "VY-2", "The system shall reject all logins.");
        when(similarity.allPairsAboveThreshold(anyDouble(), anyInt())).thenReturn(List.of(pair));
        when(similarity.pairsInvolving(any(), anyDouble())).thenReturn(List.of(pair));
        when(aiUsage.tryConsume()).thenReturn(true);
        when(gapRules.enabledByRuleKey()).thenReturn(Map.of());

        OpenAiLlmAdjudicator aiOff = new OpenAiLlmAdjudicator(mock(com.vyoog.ai.ModelGateway.class), new ObjectMapper()); // a gateway that is not configured: configured() is false
        var detector = new ConflictingRequirementsDetector(similarity, Optional.of(aiOff), gapRules, aiUsage);
        sweep = new DetectionSweepService(List.of(detector), gapRules, reconciler, aiUsage, new SimpleMeterRegistry());

        root = (Logger) LoggerFactory.getLogger("com.vyoog");
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        root.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(logs);
    }

    @Test
    void VYB0911_AC3_withAiOffARequirementWriteLogsNoErrorAndNoStackTrace() {
        var results = sweep.rescanObject(UUID.randomUUID());

        assertThat(results).singleElement().satisfies(r -> assertThat(r.unavailable()).isTrue());
        assertThat(logs.list).filteredOn(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
            .as("no WARN or ERROR for a deliberately switched-off feature").isEmpty();
        assertThat(logs.list).filteredOn(e -> e.getThrowableProxy() != null).as("no stack trace").isEmpty();
    }

    @Test
    void VYB0911_AC3_withAiOffTheNightlySweepAlsoLogsNoWarningOrErrorAndNoStackTrace() {
        var results = sweep.triggerManualSweep();

        assertThat(results).singleElement().satisfies(r -> assertThat(r.unavailable()).isTrue());
        assertThat(logs.list).filteredOn(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).isEmpty();
        assertThat(logs.list).filteredOn(e -> e.getThrowableProxy() != null).isEmpty();
    }

    @Test
    void VYB0911_AC3_withAiOffNoAiCallBudgetIsSpentAndNoSimilarityQueryIsRun() {
        sweep.rescanObject(UUID.randomUUID());

        verify(aiUsage, never()).tryConsume();
        verify(similarity, never()).pairsInvolving(any(), anyDouble());
    }

    @Test
    void VYB0911_AC3_theOneNoticeThatTheFeatureIsOffIsLoggedOnceNotOnEveryWrite() {
        sweep.rescanObject(UUID.randomUUID());
        sweep.rescanObject(UUID.randomUUID());
        sweep.rescanObject(UUID.randomUUID());

        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.INFO)
            .filteredOn(e -> e.getFormattedMessage().contains("AI_ENABLED"))
            .as("announced once, so an operator can still tell conflict detection is off").hasSize(1);
    }
}
