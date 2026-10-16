package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vyoog.platform.RateLimitExceededException;
import com.vyoog.platform.RateLimiter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * VYB-0921 (D54): the counted rate limit, against a real PostgreSQL. "At most N calls per window for a key", decided by one atomic
 * statement, so threads and instances cannot both be let through past the limit.
 */
class RateLimiterWindowIT extends IntegrationTestBase {

    @Autowired RateLimiter limiter;

    private static final Duration HOUR = Duration.ofHours(1);

    @Test
    void VYB0921_AC1_aKeyMayCallExactlyTheLimitThenIsToldHowLongToWaitAndOtherKeysAreUnaffected() {
        String key = unique("window");
        for (int i = 1; i <= 3; i++) assertThat(limiter.checkWindow(key, 3, HOUR)).as("call " + i).isNull();

        Duration wait = limiter.checkWindow(key, 3, HOUR);
        assertThat(wait).as("the fourth is refused").isNotNull();
        assertThat(wait).isPositive().isLessThanOrEqualTo(HOUR);
        assertThat(limiter.checkWindow(key, 3, HOUR)).as("and the fifth, even though refused calls count").isNotNull();

        assertThat(limiter.checkWindow(unique("other"), 3, HOUR)).as("another key has its own count").isNull();
        assertThatThrownBy(() -> limiter.requireWithinWindow(key, 3, HOUR)).isInstanceOf(RateLimitExceededException.class)
            .satisfies(e -> assertThat(((RateLimitExceededException) e).retryAfter()).isPositive());
    }

    @Test
    void VYB0921_AC2_theCountStartsAgainWhenTheWindowEnds() throws Exception {
        String key = unique("rollover");
        Duration window = Duration.ofSeconds(2);
        // start just after a window begins, so that the two calls below are in one window
        Thread.sleep(2100 - (System.currentTimeMillis() % 2000) + 50);
        assertThat(limiter.checkWindow(key, 1, window)).isNull();
        Duration wait = limiter.checkWindow(key, 1, window);
        assertThat(wait).isNotNull();
        assertThat(wait).isLessThanOrEqualTo(window);

        Thread.sleep(wait.toMillis() + 150);
        assertThat(limiter.checkWindow(key, 1, window)).as("a new window").isNull();
    }

    @Test
    void VYB0921_AC3_manyThreadsAtOnceCannotGetMoreThroughThanTheLimit() throws Exception {
        String key = unique("race");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                Callable<Boolean> call = () -> (i0(limiter, key) == null);
                results.add(pool.submit(call));
            }
            int allowed = 0;
            for (Future<Boolean> f : results) if (f.get()) allowed++;
            assertThat(allowed).as("of 40 at once, only the limit gets through").isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
    }

    private static Duration i0(RateLimiter limiter, String key) {
        return limiter.checkWindow(key, 10, HOUR);
    }

    @Test
    void VYB0921_AC4_pruneRemovesWindowsNothingCanStillBeCounting() {
        String key = unique("old");
        jdbc.update("INSERT INTO rate_limit_window (key, window_start, hits) VALUES (?, clock_timestamp() - interval '3 hours', 5)", key);
        String fresh = unique("fresh");
        limiter.checkWindow(fresh, 5, HOUR);

        assertThat(limiter.prune()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rate_limit_window WHERE key = ?", Integer.class, key)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rate_limit_window WHERE key = ?", Integer.class, fresh)).isEqualTo(1);
    }
}
