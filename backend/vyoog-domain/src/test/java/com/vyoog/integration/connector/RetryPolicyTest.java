package com.vyoog.integration.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** VYB-0913 (F40): what is retried, and how long the waits are. */
class RetryPolicyTest {

    private final RetryPolicy policy =
        new RetryPolicy(4, Duration.ofMillis(500), Duration.ofSeconds(30), Duration.ofSeconds(10));

    @Test
    void VYB0913_AC2_onlyTheStatusesThatMeanNotNowAreRetried() {
        for (int status : new int[] {408, 425, 429, 500, 502, 503, 504}) assertThat(policy.retryable(status)).as("" + status).isTrue();
        for (int status : new int[] {200, 201, 301, 302, 400, 401, 403, 404, 409, 422, 501, 505}) {
            assertThat(policy.retryable(status)).as("" + status).isFalse();
        }
    }

    @Test
    void VYB0913_AC2_theWaitGrowsExponentiallyAndStaysInsideItsJitterBand() {
        // equal jitter: between half of the step and the whole step
        assertThat(policy.delayBefore(1, null, () -> 0.0)).isEqualTo(Duration.ofMillis(250));
        assertThat(policy.delayBefore(1, null, () -> 1.0)).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.delayBefore(2, null, () -> 0.0)).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.delayBefore(2, null, () -> 1.0)).isEqualTo(Duration.ofMillis(1000));
        assertThat(policy.delayBefore(3, null, () -> 1.0)).isEqualTo(Duration.ofMillis(2000));
    }

    @Test
    void VYB0913_AC2_theWaitIsNeverLongerThanTheCapHoweverManyAttemptsHaveFailed() {
        assertThat(policy.delayBefore(10, null, () -> 1.0)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.delayBefore(500, null, () -> 1.0)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void VYB0913_AC2_aLongerRetryAfterIsHonouredButCapped() {
        assertThat(policy.delayBefore(1, Duration.ofSeconds(5), () -> 0.0)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.delayBefore(1, Duration.ofMillis(10), () -> 1.0)).as("a shorter Retry-After does not shorten the backoff")
            .isEqualTo(Duration.ofMillis(500));
        assertThat(policy.delayBefore(1, Duration.ofMinutes(10), () -> 0.0)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void VYB0913_AC2_aPolicyThatCouldNotWorkIsRefused() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ofMillis(1), Duration.ofSeconds(1), Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofMillis(1), Duration.ofSeconds(1), Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
