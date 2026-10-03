package com.vyoog.platform;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * VYB-0909 (F33-F35): lets a scheduled job run on exactly one application instance at a time.
 *
 * <p>The lease is one row per job in {@code scheduler_lock} (V036), taken with a single atomic
 * {@code INSERT ... ON CONFLICT DO UPDATE ... WHERE the lease has lapsed}, using the database's
 * clock so instances with skewed clocks agree. An instance that dies while holding it stops holding
 * it when {@code atMost} passes.
 *
 * <p>Two durations, because a job can be too short as well as too long:
 * <ul>
 *   <li>{@code atMost} is the lease. Set it above how long the job can legitimately run.
 *   <li>{@code atLeast} keeps the lease for that long after the job starts even if the job finishes
 *       sooner. A nightly cron fires on every instance at nearly the same moment; without it, a job
 *       that takes two seconds would be released before the slower instance's trigger arrives, and
 *       would run twice. Use 0 for a job that is meant to run back to back (the outbox relay).
 * </ul>
 *
 * <p>The lock is taken and released in transactions of their own, so the job is free to use (and
 * commit) its own transaction and the lease outlasts it: another instance cannot start until the
 * first one's work is committed. If the job throws, the lease is still released and the exception
 * propagates as it did before.
 */
@Component
public class SchedulerLock {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLock.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;

    public SchedulerLock(JdbcTemplate jdbc, PlatformTransactionManager txManager, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.meters = meters;
    }

    /** @return true if this instance held the lease and ran the job; false if another instance holds it. */
    public boolean runExclusive(String name, Duration atMost, Duration atLeast, Runnable job) {
        String token = UUID.randomUUID().toString();
        if (!acquire(name, token, atMost)) {
            meters.counter("vyoog.scheduler.runs", "job", name, "outcome", "skipped").increment();
            log.debug("[scheduler] {} is held by another instance — skipping this run", name);
            return false;
        }
        try {
            job.run();
            meters.counter("vyoog.scheduler.runs", "job", name, "outcome", "ran").increment();
            return true;
        } catch (RuntimeException e) {
            meters.counter("vyoog.scheduler.runs", "job", name, "outcome", "failed").increment();
            throw e;
        } finally {
            release(name, token, atLeast);
        }
    }

    private boolean acquire(String name, String token, Duration atMost) {
        Integer taken = tx.execute(status -> jdbc.update("""
            INSERT INTO scheduler_lock (name, locked_by, locked_at, locked_until)
            VALUES (?, ?, clock_timestamp(), clock_timestamp() + (? * interval '1 millisecond'))
            ON CONFLICT (name) DO UPDATE
              SET locked_by = EXCLUDED.locked_by, locked_at = EXCLUDED.locked_at, locked_until = EXCLUDED.locked_until
              WHERE scheduler_lock.locked_until <= clock_timestamp()
            """, name, token, atMost.toMillis()));
        return taken != null && taken == 1;
    }

    /** Ends the lease now, or at {@code atLeast} after it began if that is later. Never throws. */
    private void release(String name, String token, Duration atLeast) {
        try {
            tx.executeWithoutResult(status -> jdbc.update("""
                UPDATE scheduler_lock
                   SET locked_until = GREATEST(locked_at + (? * interval '1 millisecond'), clock_timestamp())
                 WHERE name = ? AND locked_by = ?
                """, atLeast.toMillis(), name, token));
        } catch (RuntimeException e) {
            // The lease lapses by itself at atMost; failing to shorten it must not fail the job.
            log.warn("[scheduler] could not release {}; it will lapse on its own: {}", name, e.getMessage());
        }
    }
}
