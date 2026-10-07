package com.vyoog.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** VYB-0936: the circuit breaker's states, on a clock the test moves. */
class GatewayCircuitBreakerTest {

    /** A clock the test advances; nothing here waits. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-07T00:00:00Z");

        void advance(Duration d) { now = now.plus(d); }

        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    private final MutableClock clock = new MutableClock();
    private final GatewayCircuitBreaker breaker = new GatewayCircuitBreaker("chat", 3, Duration.ofSeconds(60), clock);

    @Test
    void VYB0936_AC8_opensAfterTheThresholdOfFailuresInARowAndRefusesCalls() {
        for (int i = 0; i < 2; i++) {
            assertThat(breaker.tryAcquire()).isTrue();
            breaker.recordFailure();
        }
        assertThat(breaker.state()).isEqualTo(GatewayCircuitBreaker.State.CLOSED);

        assertThat(breaker.tryAcquire()).isTrue();
        breaker.recordFailure();

        assertThat(breaker.state()).isEqualTo(GatewayCircuitBreaker.State.OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
        assertThat(breaker.secondsUntilTrial()).isBetween(1L, 61L);
    }

    @Test
    void VYB0936_AC8_aSuccessInBetweenResetsTheCount() {
        for (int i = 0; i < 2; i++) { breaker.tryAcquire(); breaker.recordFailure(); }
        breaker.tryAcquire();
        breaker.recordSuccess();
        for (int i = 0; i < 2; i++) { breaker.tryAcquire(); breaker.recordFailure(); }

        assertThat(breaker.state()).isEqualTo(GatewayCircuitBreaker.State.CLOSED);
    }

    @Test
    void VYB0936_AC8_afterTheOpenPeriodExactlyOneTrialCallIsLetThrough() {
        open();
        clock.advance(Duration.ofSeconds(59));
        assertThat(breaker.tryAcquire()).isFalse();

        clock.advance(Duration.ofSeconds(2));
        assertThat(breaker.state()).isEqualTo(GatewayCircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.tryAcquire()).as("the trial call").isTrue();
        assertThat(breaker.tryAcquire()).as("a second call while the trial is out").isFalse();
    }

    @Test
    void VYB0936_AC8_aSuccessfulTrialClosesItAndAFailedTrialOpensItAgain() {
        open();
        clock.advance(Duration.ofSeconds(61));
        breaker.tryAcquire();
        breaker.recordSuccess();
        assertThat(breaker.state()).isEqualTo(GatewayCircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();

        open();
        clock.advance(Duration.ofSeconds(61));
        breaker.tryAcquire();
        breaker.recordFailure();
        assertThat(breaker.state()).isEqualTo(GatewayCircuitBreaker.State.OPEN);
        assertThat(breaker.tryAcquire()).as("it waits out another full period").isFalse();
        clock.advance(Duration.ofSeconds(61));
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void VYB0936_AC8_anAbandonedTrialFreesTheSlotWithoutChangingTheVerdict() {
        open();
        clock.advance(Duration.ofSeconds(61));
        breaker.tryAcquire();
        breaker.release();

        assertThat(breaker.tryAcquire()).as("another trial may go").isTrue();
    }

    @Test
    void VYB0936_AC8_refusesNonsenseSettings() {
        assertThatThrownBy(() -> new GatewayCircuitBreaker("x", 0, Duration.ofSeconds(1), clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayCircuitBreaker("x", 1, Duration.ZERO, clock)).isInstanceOf(IllegalArgumentException.class);
    }

    private void open() {
        for (int i = 0; i < 3; i++) { breaker.tryAcquire(); breaker.recordFailure(); }
    }
}
