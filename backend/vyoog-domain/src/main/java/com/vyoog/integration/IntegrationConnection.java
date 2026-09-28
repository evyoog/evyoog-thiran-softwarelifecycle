package com.vyoog.integration;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * VYB-0740: one row per external system this deployment could talk to — seeded in
 * V007, {@code connected} starting false for every one of them, which is what makes
 * "an unconfigured system reports not connected" (AC1) true without any special-case
 * code: there is simply no path anywhere that flips it to true except an explicit
 * configuration action.
 *
 * <p>{@code config} (V001) held connection-specific settings from the start — a
 * push URL for an OUTBOUND system, say — but nothing on this entity ever mapped it
 * until VYB-0465/0507 (session 16) needed somewhere real to put "planning"'s push
 * URL. Plain JSON text, same {@code SqlTypes.JSON} pattern already used for
 * {@code AuditEvent.before}/{@code after}.
 */
@Entity
@Table(name = "integration_connection")
public class IntegrationConnection {

    @Id
    private String key;

    @Column(nullable = false)
    private boolean connected;

    private String owns;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String config;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction = Direction.INBOUND;

    @Column(name = "webhook_secret")
    private String webhookSecret;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    @Column(nullable = false)
    private boolean degraded;

    public enum Direction { INBOUND, OUTBOUND, BOTH }

    /** VYB-0743 AC1: this many consecutive failures before a connection is marked degraded. */
    public static final int DEGRADE_AFTER_FAILURES = 3;

    protected IntegrationConnection() {}

    /** VYB-0839: the one place a new row (beyond V007's four seeded ones) gets created. */
    public IntegrationConnection(String key, String owns, Direction direction) {
        this.key = key;
        this.owns = owns;
        this.direction = direction == null ? Direction.INBOUND : direction;
        this.connected = false;
    }

    public String getKey() { return key; }
    public boolean isConnected() { return connected; }
    public void setConnected(boolean connected) {
        this.connected = connected;
        if (connected) { this.failureCount = 0; this.degraded = false; this.lastError = null; }
    }
    public String getOwns() { return owns; }
    public void setOwns(String owns) { this.owns = owns; }
    public String getConfig() { return config; }
    public void setConfig(String config) { this.config = config; }
    public Direction getDirection() { return direction; }
    public void setDirection(Direction direction) { this.direction = direction; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    public int getFailureCount() { return failureCount; }
    public String getLastError() { return lastError; }
    public Instant getLastErrorAt() { return lastErrorAt; }
    public boolean isDegraded() { return degraded; }

    /** VYB-0743: a failure is recorded and surfaced, never silently retried forever. */
    public void recordFailure(String error) {
        this.failureCount++;
        this.lastError = error;
        this.lastErrorAt = Instant.now();
        if (this.failureCount >= DEGRADE_AFTER_FAILURES) {
            this.degraded = true;
        }
    }

    public void recordSuccess() {
        this.failureCount = 0;
        this.degraded = false;
    }
}
