package com.vyoog.integration.connector;

import java.time.Duration;
import java.util.Set;
import java.util.function.DoubleSupplier;

/**
 * VYB-0913: how an outbound call is retried. Exponential backoff with jitter, so a receiver that is
 * struggling is not hit by every instance at the same instant, and a hard cap so a stuck receiver
 * costs a bounded amount of time.
 *
 * <p>What is retried is what can plausibly succeed next time: a connection or timeout failure, and
 * the statuses that mean "not now" (408, 425, 429, 500, 502, 503, 504). Everything else, including
 * every other 4xx and every redirect, is a definite answer and is not repeated.
 */
public record RetryPolicy(int maxAttempts, Duration initialDelay, Duration maxDelay, Duration requestTimeout) {

    public static final RetryPolicy DEFAULT =
        new RetryPolicy(4, Duration.ofMillis(500), Duration.ofSeconds(30), Duration.ofSeconds(10));

    private static final Set<Integer> RETRYABLE_STATUS = Set.of(408, 425, 429, 500, 502, 503, 504);

    public RetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1, got " + maxAttempts);
        if (initialDelay.isNegative() || maxDelay.isNegative() || maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("delays must be non-negative with maxDelay >= initialDelay");
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
    }

    public boolean retryable(int httpStatus) {
        return RETRYABLE_STATUS.contains(httpStatus);
    }

    /**
     * The wait before the next attempt, after {@code attemptsMade} attempts have failed.
     * Equal jitter: half of the exponential step is fixed and half is random, so the wait is never
     * near zero and never exactly the same on two instances. A receiver's {@code Retry-After} is
     * honoured when it is longer, up to {@link #maxDelay}.
     */
    public Duration delayBefore(int attemptsMade, Duration retryAfter, DoubleSupplier random01) {
        long stepMillis = initialDelay.toMillis() << Math.min(Math.max(attemptsMade - 1, 0), 20);
        long capMillis = Math.min(maxDelay.toMillis(), stepMillis);
        long jittered = capMillis / 2 + (long) (random01.getAsDouble() * (capMillis - capMillis / 2));
        long wanted = retryAfter == null ? jittered : Math.max(jittered, retryAfter.toMillis());
        return Duration.ofMillis(Math.min(maxDelay.toMillis(), wanted));
    }
}
