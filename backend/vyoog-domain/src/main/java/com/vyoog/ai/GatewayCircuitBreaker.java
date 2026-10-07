package com.vyoog.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * VYB-0936: stops calling a provider that keeps failing, so a down provider costs one fast refusal instead of a
 * full timeout-and-retry on every request, and gets room to recover.
 *
 * <p>CLOSED: calls go through; {@code failureThreshold} failed calls in a row open it. OPEN: calls are refused
 * without being sent until {@code openFor} has passed. Then HALF_OPEN: exactly one trial call is let through; its
 * success closes the breaker, its failure opens it again for another {@code openFor}.
 *
 * <p>A "failed call" is one whose retries were all spent on connection failures, timeouts or a retryable status.
 * An answer from the provider that retrying cannot change (a 400, a 401) shows the provider is up and is recorded
 * as a success here; it is the caller's problem, not the provider's health.
 *
 * <p>State is in memory, per application instance. A restart closes it.
 */
public final class GatewayCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int failureThreshold;
    private final Duration openFor;
    private final Clock clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;
    private boolean trialInFlight;

    public GatewayCircuitBreaker(String name, int failureThreshold, Duration openFor, Clock clock) {
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be at least 1");
        if (openFor.isNegative() || openFor.isZero()) throw new IllegalArgumentException("openFor must be positive");
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.openFor = openFor;
        this.clock = clock;
    }

    public String name() {
        return name;
    }

    /** @return true if a call may be made now. A true answer must be followed by {@link #recordSuccess}, {@link #recordFailure} or {@link #release}. */
    public synchronized boolean tryAcquire() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (clock.instant().isBefore(openedAt.plus(openFor))) return false;
                state = State.HALF_OPEN;
                trialInFlight = true;
                return true;
            default: // HALF_OPEN
                if (trialInFlight) return false;
                trialInFlight = true;
                return true;
        }
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        trialInFlight = false;
        state = State.CLOSED;
    }

    public synchronized void recordFailure() {
        trialInFlight = false;
        if (state == State.HALF_OPEN) {
            open();
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) open();
    }

    /** The call was abandoned (the thread was interrupted): neither a success nor a failure of the provider. */
    public synchronized void release() {
        trialInFlight = false;
    }

    private void open() {
        state = State.OPEN;
        openedAt = clock.instant();
    }

    public synchronized State state() {
        if (state == State.OPEN && !clock.instant().isBefore(openedAt.plus(openFor))) return State.HALF_OPEN;
        return state;
    }

    /** Seconds until a trial call is let through; 0 when the breaker is not open. */
    public synchronized long secondsUntilTrial() {
        if (state != State.OPEN) return 0;
        long left = Duration.between(clock.instant(), openedAt.plus(openFor)).toSeconds() + 1;
        return Math.max(left, 0);
    }
}
