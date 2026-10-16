package com.vyoog.platform;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0908 (F09): a per-key cooldown shared by every instance of the application.
 *
 * <p>The state is one row per key in {@code rate_limit_hit} (V035) and the whole decision is a
 * single atomic SQL statement, so two instances (or two threads) racing on the same key cannot both
 * be let through: {@code INSERT ... ON CONFLICT DO UPDATE ... WHERE last_call is old enough} succeeds
 * for exactly one of them. The time comes from the database, not the JVM, so instances with
 * different clocks agree. It used to be an in-memory map, which made every cooldown per instance and
 * forgot it on restart.
 *
 * <p>Each call runs in its own transaction ({@code REQUIRES_NEW}): an attempt counts even if the
 * caller later fails and rolls back, otherwise a request that always errors would never be limited.
 * Call it through the Spring bean (from another class), not from inside this one.
 */
@Component
public class RateLimiter {

    /** No cooldown in this application is anywhere near this long, so older rows are dead weight. */
    static final Duration RETENTION = Duration.ofHours(1);
    private static final int PRUNE_ONE_IN = 200;

    private final JdbcTemplate jdbc;

    public RateLimiter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Outcome(boolean allowed, Duration remaining) {}

    /**
     * @return null if the call may proceed (and is recorded as having just happened);
     *     otherwise the duration the caller must still wait.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Duration checkAndRecord(String key, Duration cooldown) {
        long cooldownMillis = cooldown.toMillis();
        // One statement: try to take the slot; if it was not taken, read how long ago the holder did.
        // The final SELECT sees the snapshot from before the CTE's write, so on a refusal last_call is
        // the holder's time, and on success the first column says so.
        List<Outcome> rows = jdbc.query("""
            WITH attempt AS (
              INSERT INTO rate_limit_hit (key, last_call) VALUES (?, clock_timestamp())
              ON CONFLICT (key) DO UPDATE SET last_call = clock_timestamp()
                WHERE rate_limit_hit.last_call <= clock_timestamp() - (? * interval '1 millisecond')
              RETURNING key
            )
            SELECT (SELECT count(*) FROM attempt) > 0 AS allowed,
                   COALESCE(
                     (SELECT ? - (extract(epoch FROM clock_timestamp() - last_call) * 1000)::bigint
                        FROM rate_limit_hit WHERE key = ?), 0) AS remaining_ms
            """,
            (rs, n) -> new Outcome(rs.getBoolean("allowed"), Duration.ofMillis(Math.max(rs.getLong("remaining_ms"), 1))),
            key, cooldownMillis, cooldownMillis, key);
        Outcome outcome = rows.get(0);
        if (outcome.allowed()) {
            if (ThreadLocalRandom.current().nextInt(PRUNE_ONE_IN) == 0) prune();
            return null;
        }
        return outcome.remaining();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requireNotLimited(String key, Duration cooldown) {
        Duration remaining = checkAndRecord(key, cooldown);
        if (remaining != null) {
            throw new RateLimitExceededException(remaining);
        }
    }

    /**
     * VYB-0921 (D54): a counted limit: at most {@code limit} calls per {@code window} for this key, in fixed windows that start on a multiple
     * of the window's length. Every call counts, refused ones too, in one atomic statement (so instances and threads agree), in its own
     * transaction (so a call that later fails still counted).
     *
     * @return null if the call may proceed; otherwise how long until the window ends and the count starts again
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Duration checkWindow(String key, int limit, Duration window) {
        long seconds = Math.max(1, window.toSeconds());
        List<long[]> rows = jdbc.query("""
            INSERT INTO rate_limit_window (key, window_start, hits)
            VALUES (?, to_timestamp(floor(extract(epoch FROM clock_timestamp()) / ?) * ?), 1)
            ON CONFLICT (key, window_start) DO UPDATE SET hits = rate_limit_window.hits + 1
            RETURNING hits, ceil(extract(epoch FROM (window_start + make_interval(secs => ?) - clock_timestamp())))::bigint AS remaining
            """, (rs, n) -> new long[] {rs.getLong("hits"), rs.getLong("remaining")}, key, seconds, seconds, (double) seconds);
        long[] row = rows.get(0);
        if (ThreadLocalRandom.current().nextInt(PRUNE_ONE_IN) == 0) prune();
        return row[0] <= limit ? null : Duration.ofSeconds(Math.max(1, row[1]));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requireWithinWindow(String key, int limit, Duration window) {
        Duration retryAfter = checkWindow(key, limit, window);
        if (retryAfter != null) {
            throw new RateLimitExceededException(retryAfter);
        }
    }

    /** Deletes rows no cooldown or window can still be counting. Returns how many. Also runs occasionally by itself. */
    public int prune() {
        int cooldowns = jdbc.update("DELETE FROM rate_limit_hit WHERE last_call < clock_timestamp() - (? * interval '1 millisecond')",
            RETENTION.toMillis());
        int windows = jdbc.update("DELETE FROM rate_limit_window WHERE window_start < clock_timestamp() - (? * interval '1 millisecond')",
            RETENTION.toMillis());
        return cooldowns + windows;
    }
}
