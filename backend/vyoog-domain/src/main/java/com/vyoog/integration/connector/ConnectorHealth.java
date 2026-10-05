package com.vyoog.integration.connector;

import java.time.Instant;

/**
 * VYB-0913: where a connection stands, for the people who administer it (the screen is VYB-0917).
 *
 * @param state NOT_CONNECTED: not configured, or configured but nothing has succeeded yet. HEALTHY: the
 *     last operations are succeeding. DEGRADED: {@code IntegrationConnection.DEGRADE_AFTER_FAILURES} failed
 *     operations in a row, until one succeeds.
 * @param notConfiguredReason why nothing can be sent, or null when the configuration is usable. Always null for an
 *     INBOUND-only connection, which sends nothing and so has no outbound configuration to lack.
 */
public record ConnectorHealth(
    String connectionKey,
    State state,
    String notConfiguredReason,
    int consecutiveFailures,
    String lastError,
    Instant lastErrorAt,
    Instant lastSuccessAt) {

    public enum State { NOT_CONNECTED, HEALTHY, DEGRADED }
}
