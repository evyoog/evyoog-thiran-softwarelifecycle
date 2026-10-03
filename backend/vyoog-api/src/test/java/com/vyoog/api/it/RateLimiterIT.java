package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vyoog.platform.RateLimitExceededException;
import com.vyoog.platform.RateLimiter;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** VYB-0908 (F09): the rate limiter's state lives in the database, so every instance shares it. */
class RateLimiterIT extends IntegrationTestBase {

    @Autowired RateLimiter limiter;
    @Autowired PlatformTransactionManager transactions;

    private static final Duration LONG = Duration.ofSeconds(30);

    @Test
    void VYB0908_AC5_theFirstCallIsAllowedAndASecondWithinTheCooldownIsToldHowLongToWait() {
        String key = unique("limit");
        assertThat(limiter.checkAndRecord(key, LONG)).isNull();
        Duration wait = limiter.checkAndRecord(key, LONG);
        assertThat(wait).isNotNull();
        assertThat(wait).isPositive().isLessThanOrEqualTo(LONG).isGreaterThan(Duration.ofSeconds(20));
    }

    @Test
    void VYB0908_AC5_aCallIsAllowedAgainOnceTheCooldownHasPassed() throws Exception {
        String key = unique("limit");
        Duration shortCooldown = Duration.ofMillis(300);
        assertThat(limiter.checkAndRecord(key, shortCooldown)).isNull();
        assertThat(limiter.checkAndRecord(key, shortCooldown)).isNotNull();
        Thread.sleep(450);
        assertThat(limiter.checkAndRecord(key, shortCooldown)).isNull();
    }

    @Test
    void VYB0908_AC5_differentKeysDoNotAffectEachOther() {
        assertThat(limiter.checkAndRecord(unique("a"), LONG)).isNull();
        assertThat(limiter.checkAndRecord(unique("b"), LONG)).isNull();
    }

    @Test
    void VYB0908_AC6_aSecondInstanceSharingTheDatabaseSeesTheFirstInstancesCall() {
        String key = unique("shared");
        RateLimiter otherInstance = new RateLimiter(jdbc); // another JVM would build exactly this
        assertThat(limiter.checkAndRecord(key, LONG)).isNull();
        assertThat(otherInstance.checkAndRecord(key, LONG)).as("the other instance is limited too").isNotNull();
    }

    @Test
    void VYB0908_AC6_ofManyCallsRacingOnTheSameKeyExactlyOneIsLetThrough() throws Exception {
        String key = unique("race");
        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Duration>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            RateLimiter instance = i % 2 == 0 ? limiter : new RateLimiter(jdbc);
            results.add(pool.submit(() -> {
                start.await();
                return instance.checkAndRecord(key, LONG);
            }));
        }
        start.countDown();
        int allowed = 0;
        for (Future<Duration> f : results) if (f.get() == null) allowed++;
        pool.shutdown();
        assertThat(allowed).isEqualTo(1);
    }

    @Test
    void VYB0908_AC7_aRefusedAttemptDoesNotExtendTheCooldown() {
        String key = unique("noextend");
        limiter.checkAndRecord(key, LONG);
        Timestamp first = jdbc.queryForObject("SELECT last_call FROM rate_limit_hit WHERE key = ?", Timestamp.class, key);
        limiter.checkAndRecord(key, LONG);
        limiter.checkAndRecord(key, LONG);
        assertThat(jdbc.queryForObject("SELECT last_call FROM rate_limit_hit WHERE key = ?", Timestamp.class, key)).isEqualTo(first);
    }

    @Test
    void VYB0908_AC7_requireNotLimitedThrowsWithTheRemainingWait() {
        String key = unique("require");
        limiter.requireNotLimited(key, LONG);
        assertThatThrownBy(() -> limiter.requireNotLimited(key, LONG))
            .isInstanceOfSatisfying(RateLimitExceededException.class, e -> assertThat(e.getMessage()).isNotBlank());
    }

    @Test
    void VYB0908_AC7_aCallCountsEvenIfTheCallersTransactionLaterRollsBack() {
        String key = unique("rollback");
        new TransactionTemplate(transactions).execute(status -> {
            limiter.checkAndRecord(key, LONG);
            status.setRollbackOnly();
            return null;
        });
        assertThat(limiter.checkAndRecord(key, LONG)).as("the rolled-back attempt still counted").isNotNull();
    }

    @Test
    void VYB0908_AC8_pruningRemovesOnlyRowsNoCooldownCouldStillBeWaitingOn() {
        String old = unique("old"), fresh = unique("fresh");
        jdbc.update("INSERT INTO rate_limit_hit (key, last_call) VALUES (?, now() - interval '3 hours')", old);
        limiter.checkAndRecord(fresh, LONG);

        assertThat(limiter.prune()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rate_limit_hit WHERE key = ?", Integer.class, old)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rate_limit_hit WHERE key = ?", Integer.class, fresh)).isEqualTo(1);
    }
}
