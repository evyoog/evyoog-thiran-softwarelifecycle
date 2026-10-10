package com.vyoog.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vyoog.ai.AiUsageTracker;
import com.vyoog.platform.tx.AfterCommitRunner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * VYB-0940 (F31): a write path rescans the object it changed, inside its own transaction. A detector that calls a model is not
 * run there; it runs after the commit, so the model call is never inside the transaction.
 */
class DeferredModelDetectionTest {

    static final class Probe implements Detector {
        final String key;
        final boolean model;
        final List<String> log;

        Probe(String key, boolean model, List<String> log) {
            this.key = key;
            this.model = model;
            this.log = log;
        }

        @Override public String ruleKey() { return key; }
        @Override public List<Candidate> scan() { return List.of(); }
        @Override public List<Candidate> scanOne(UUID objectId) {
            log.add(key + " scanned, in transaction: " + com.vyoog.platform.tx.NetworkCallGuard.inTransaction());
            return List.of();
        }
        @Override public boolean callsModel() { return model; }
    }

    private final List<String> log = new ArrayList<>();
    private final List<Runnable> afterCommit = new ArrayList<>();
    private DetectionSweepService sweep;

    @BeforeEach
    void setUp() {
        GapRuleService gapRules = mock(GapRuleService.class);
        when(gapRules.enabledByRuleKey()).thenReturn(Map.of());
        FindingReconciler reconciler = mock(FindingReconciler.class);
        when(reconciler.reconcileOne(anyString(), any(), any())).thenAnswer(i ->
            new FindingReconciler.ReconcileResult(i.getArgument(0), 0, 0, 0, 0, false));
        AfterCommitRunner runner = new AfterCommitRunner(Runnable::run) {
            @Override public void run(Runnable work) {
                afterCommit.add(work);
            }
        };
        sweep = new DetectionSweepService(
            List.of(new Probe("plain", false, log), new Probe("model", true, log)), gapRules, reconciler,
            mock(AiUsageTracker.class), new SimpleMeterRegistry(), runner);
    }

    @AfterEach
    void closeTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void VYB0940_AC23_insideATransactionTheModelDetectorWaitsForTheCommitAndTheOthersRunNow() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        var results = sweep.rescanObject(UUID.randomUUID());

        assertThat(results).extracting(FindingReconciler.ReconcileResult::ruleKey).containsExactly("plain");
        assertThat(log).containsExactly("plain scanned, in transaction: true");
        assertThat(afterCommit).as("one piece of work for the model detector").hasSize(1);

        TransactionSynchronizationManager.setActualTransactionActive(false);
        afterCommit.get(0).run();
        assertThat(log).containsExactly("plain scanned, in transaction: true", "model scanned, in transaction: false");
    }

    @Test
    void VYB0940_AC24_withNoTransactionEverythingRunsAtOnceAsBefore() {
        var results = sweep.rescanObject(UUID.randomUUID());

        assertThat(results).extracting(FindingReconciler.ReconcileResult::ruleKey).containsExactly("plain", "model");
        assertThat(afterCommit).isEmpty();
    }

    @Test
    void VYB0940_AC25_aDetectorIsNotDeferredUnlessItCallsAModel() {
        assertThat(new Probe("x", false, log).callsModel()).isFalse();
        assertThat(new com.vyoog.detection.detectors.NoDesignDetector(mock(org.springframework.jdbc.core.JdbcTemplate.class)).callsModel()).isFalse();
    }
}
