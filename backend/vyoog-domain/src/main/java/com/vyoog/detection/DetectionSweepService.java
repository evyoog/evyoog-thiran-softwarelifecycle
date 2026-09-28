package com.vyoog.detection;

import com.vyoog.ai.AiUsageTracker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Runs every enabled {@link Detector} and reconciles its output — either the whole
 * dataset ({@link #sweep}) or one changed object ({@link #rescanObject}, VYB-0161).
 *
 * <p>Provisionally living in {@code vyoog-domain}/scheduled from {@code vyoog-api}
 * rather than in {@code vyoog-worker} — that module has no Spring Boot application of
 * its own yet (see README's module layout). Move it once vyoog-worker is stood up as
 * its own deployable process.
 */
@Service
public class DetectionSweepService {

    private static final Logger log = LoggerFactory.getLogger(DetectionSweepService.class);

    /** VYB-0163 AC2: a manual trigger inside this window of the last run is refused. */
    static final Duration MANUAL_RESCAN_COOLDOWN = Duration.ofSeconds(30);

    private final List<Detector> detectors;
    private final GapRuleService gapRules;
    private final FindingReconciler reconciler;
    private final AiUsageTracker aiUsage;
    private final MeterRegistry meters;

    // VYB-0163 AC1: two sweeps for this deployment never run in parallel. A single
    // AtomicBoolean is enough for a single-instance deployment — this would need a
    // distributed lock (e.g. a Postgres advisory lock) if this ever runs on more than
    // one instance at once.
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant lastCompletedAt;

    public DetectionSweepService(List<Detector> detectors, GapRuleService gapRules,
                                  FindingReconciler reconciler, AiUsageTracker aiUsage, MeterRegistry meters) {
        this.detectors = detectors;
        this.gapRules = gapRules;
        this.reconciler = reconciler;
        this.aiUsage = aiUsage;
        this.meters = meters;
    }

    /** Manual trigger — subject to the concurrency guard and the rate limit. */
    public List<FindingReconciler.ReconcileResult> triggerManualSweep() {
        if (lastCompletedAt != null
                && Duration.between(lastCompletedAt, Instant.now()).compareTo(MANUAL_RESCAN_COOLDOWN) < 0) {
            throw new IllegalStateException(
                "A sweep completed less than %d seconds ago — try again shortly"
                    .formatted(MANUAL_RESCAN_COOLDOWN.getSeconds()));
        }
        return sweep();
    }

    /** VYB-0162: reconciles anything the event path (below) missed. */
    @Scheduled(cron = "0 0 2 * * *")
    public void nightlySweep() {
        sweep();
    }

    /**
     * VYB-0161: a bounded re-evaluation of one changed object, called synchronously
     * from the write paths that change what a detector reads (see e.g.
     * {@code RequirementService}, {@code TraceGraphService}). AC2 — this is a real
     * {@code WHERE id = ?} per detector ({@link Detector#scanOne}), not the full
     * {@link #sweep} filtered down.
     *
     * <p>Not subject to the sweep's concurrency guard or rate limit — those exist to
     * stop two expensive full scans overlapping, and this is neither: it's one row's
     * worth of bounded queries, safe to run inline on every write.
     */
    public List<FindingReconciler.ReconcileResult> rescanObject(UUID objectId) {
        Map<String, Boolean> enabledByRule = gapRules.enabledByRuleKey();
        return detectors.stream()
            .filter(d -> enabledByRule.getOrDefault(d.ruleKey(), true))
            .map(d -> runOne(d, () -> reconciler.reconcileOne(d.ruleKey(), objectId, d.scanOne(objectId))))
            .toList();
    }

    /** VYB-0784: named so "a slow endpoint is attributable to a query from the trace" extends to the scheduled path too, not only the manual one an HTTP trace would already show. */
    private List<FindingReconciler.ReconcileResult> sweep() {
        Timer.Sample sample = Timer.start(meters);
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("A sweep is already running");
        }
        try {
            aiUsage.beginRun(); // VYB-0620: the per-run AI-call budget resets once, here, not per detector.
            Map<String, Boolean> enabledByRule = gapRules.enabledByRuleKey();

            List<FindingReconciler.ReconcileResult> results = detectors.stream()
                // VYB-0164 AC1: a disabled rule produces no findings. Absent = enabled by default.
                .filter(d -> enabledByRule.getOrDefault(d.ruleKey(), true))
                .map(d -> runOne(d, () -> reconciler.reconcile(d.ruleKey(), d.scan())))
                .toList();

            results.forEach(r -> {
                if (r.unavailable()) {
                    log.warn("[detection] {}: unavailable this cycle — existing findings left untouched", r.ruleKey());
                } else {
                    log.info("[detection] {}: {} opened, {} refreshed, {} reopened, {} resolved",
                        r.ruleKey(), r.opened(), r.refreshed(), r.reopened(), r.resolved());
                }
            });

            return results;
        } finally {
            lastCompletedAt = Instant.now();
            running.set(false);
            sample.stop(meters.timer("vyoog.detection.sweep.duration"));
        }
    }

    /**
     * VYB-0603 AC1: one detector's failure — an AI provider being down, or anything
     * else going wrong — never stops the others in the same sweep from running.
     * {@link DetectorUnavailableException} and any other exception both degrade to
     * "unavailable" here; an unexpected exception is exactly as unsafe to treat as
     * "found nothing" as a deliberate one is.
     */
    private FindingReconciler.ReconcileResult runOne(
            Detector d, java.util.function.Supplier<FindingReconciler.ReconcileResult> run) {
        try {
            return run.get();
        } catch (DetectorUnavailableException e) {
            log.warn("[detection] {} unavailable: {}", d.ruleKey(), e.getMessage());
            return FindingReconciler.ReconcileResult.unavailable(d.ruleKey());
        } catch (Exception e) {
            log.error("[detection] {} failed unexpectedly: {}", d.ruleKey(), e.getMessage(), e);
            return FindingReconciler.ReconcileResult.unavailable(d.ruleKey());
        }
    }
}
