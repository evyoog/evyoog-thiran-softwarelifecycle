package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vyoog.platform.SchedulerLock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * VYB-0909 (F33-F35): a scheduled job runs on one instance at a time. Every "instance" here is a
 * separate {@link SchedulerLock} object sharing only the database, which is all two real instances share.
 */
class SchedulerLockIT extends IntegrationTestBase {

    @Autowired PlatformTransactionManager transactions;

    private static final Duration LONG = Duration.ofMinutes(5);

    private SchedulerLock instance() {
        return new SchedulerLock(jdbc, transactions, new SimpleMeterRegistry());
    }

    @Test
    void VYB0909_AC2_aSecondInstanceIsRefusedWhileTheFirstIsStillRunningTheJob() {
        String job = unique("job");
        SchedulerLock a = instance(), b = instance();
        AtomicInteger ranOnB = new AtomicInteger();
        boolean[] refusedInside = new boolean[1];

        boolean ranOnA = a.runExclusive(job, LONG, Duration.ZERO, () ->
            refusedInside[0] = !b.runExclusive(job, LONG, Duration.ZERO, ranOnB::incrementAndGet));

        assertThat(ranOnA).isTrue();
        assertThat(refusedInside[0]).as("B was refused while A held the lease").isTrue();
        assertThat(ranOnB).hasValue(0);
    }

    @Test
    void VYB0909_AC2_onceTheFirstFinishesAnotherInstanceMayRunTheJob() {
        String job = unique("job");
        assertThat(instance().runExclusive(job, LONG, Duration.ZERO, () -> {})).isTrue();
        assertThat(instance().runExclusive(job, LONG, Duration.ZERO, () -> {})).isTrue();
    }

    @Test
    void VYB0909_AC2_aMinimumHoldStopsALateInstanceRerunningAJobThatFinishedQuickly() {
        String job = unique("nightly");
        AtomicInteger runs = new AtomicInteger();
        // The two instances' crons fire a moment apart; the job itself takes microseconds.
        instance().runExclusive(job, LONG, Duration.ofMinutes(10), runs::incrementAndGet);
        boolean secondRan = instance().runExclusive(job, LONG, Duration.ofMinutes(10), runs::incrementAndGet);

        assertThat(secondRan).isFalse();
        assertThat(runs).hasValue(1);
    }

    @Test
    void VYB0909_AC2_exactlyOneOfManyRacingInstancesRunsTheJob() throws Exception {
        String job = unique("race");
        int contenders = 24;
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                SchedulerLock lock = instance();
                results.add(pool.submit(() -> {
                    start.await();
                    return lock.runExclusive(job, LONG, Duration.ofMinutes(10), runs::incrementAndGet);
                }));
            }
            start.countDown();
            int ran = 0;
            for (Future<Boolean> f : results) if (f.get(30, TimeUnit.SECONDS)) ran++;
            assertThat(ran).isEqualTo(1);
            assertThat(runs).hasValue(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void VYB0909_AC2_aJobThatFailsStillReleasesTheLeaseAndTheFailureReachesTheCaller() {
        String job = unique("job");
        assertThatThrownBy(() -> instance().runExclusive(job, LONG, Duration.ZERO, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        assertThat(instance().runExclusive(job, LONG, Duration.ZERO, () -> {})).as("lease was released").isTrue();
    }

    @Test
    void VYB0909_AC2_aLeaseLeftBehindByADeadInstanceLapsesAndAnotherInstanceTakesOver() throws Exception {
        String job = unique("job");
        // An instance that took the lease and died: a row whose lease ends shortly, never released.
        jdbc.update("""
            INSERT INTO scheduler_lock (name, locked_by, locked_at, locked_until)
            VALUES (?, 'dead-instance', clock_timestamp(), clock_timestamp() + interval '300 milliseconds')""", job);

        assertThat(instance().runExclusive(job, LONG, Duration.ZERO, () -> {})).as("still held").isFalse();
        Thread.sleep(500);
        assertThat(instance().runExclusive(job, LONG, Duration.ZERO, () -> {})).as("lapsed").isTrue();
    }

    @Test
    void VYB0909_AC2_aHolderWhoseLeaseLapsedCannotReleaseTheInstanceThatTookOver() throws Exception {
        String job = unique("job");
        SchedulerLock slow = instance(), successor = instance(), third = instance();
        CountDownLatch successorHolds = new CountDownLatch(1);
        CountDownLatch slowMayFinish = new CountDownLatch(1);
        CountDownLatch successorMayFinish = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // "slow" has a 200ms lease but its job outlasts it.
            Future<Boolean> slowRun = pool.submit(() -> slow.runExclusive(job, Duration.ofMillis(200), Duration.ZERO,
                () -> await(slowMayFinish)));
            Thread.sleep(400); // slow's lease has lapsed; it does not know
            Future<Boolean> successorRun = pool.submit(() -> successor.runExclusive(job, LONG, Duration.ZERO, () -> {
                successorHolds.countDown();
                await(successorMayFinish);
            }));
            assertThat(successorHolds.await(5, TimeUnit.SECONDS)).as("successor took over the lapsed lease").isTrue();

            slowMayFinish.countDown();
            slowRun.get(10, TimeUnit.SECONDS); // slow has now run its release

            // Had slow's release cleared the successor's lease, this third instance would get in.
            assertThat(third.runExclusive(job, LONG, Duration.ZERO, () -> {})).as("successor still holds").isFalse();

            successorMayFinish.countDown();
            assertThat(successorRun.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            slowMayFinish.countDown();
            successorMayFinish.countDown();
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void VYB0909_AC2_aCallerTransactionThatRollsBackDoesNotUndoOrBlockTheLease() {
        String job = unique("job");
        SchedulerLock a = instance();
        org.springframework.transaction.support.TransactionTemplate outer =
            new org.springframework.transaction.support.TransactionTemplate(transactions);
        outer.executeWithoutResult(status -> {
            a.runExclusive(job, LONG, Duration.ofMinutes(10), () -> {});
            status.setRollbackOnly();
        });
        // The lease was taken in its own transaction, so it stands (the minimum hold still applies).
        assertThat(instance().runExclusive(job, LONG, Duration.ZERO, () -> {})).isFalse();
    }

    @Test
    void VYB0909_AC2_runsAndSkipsAreCountedForThePrometheusScrape() {
        String job = unique("job");
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SchedulerLock lock = new SchedulerLock(jdbc, transactions, meters);
        lock.runExclusive(job, LONG, Duration.ofMinutes(10), () -> {});
        lock.runExclusive(job, LONG, Duration.ofMinutes(10), () -> {});

        assertThat(meters.counter("vyoog.scheduler.runs", "job", job, "outcome", "ran").count()).isEqualTo(1.0);
        assertThat(meters.counter("vyoog.scheduler.runs", "job", job, "outcome", "skipped").count()).isEqualTo(1.0);
    }
}
