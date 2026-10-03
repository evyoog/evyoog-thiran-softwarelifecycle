package com.vyoog.integration.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.integration.IntegrationConnection;
import com.vyoog.integration.IntegrationService;
import com.vyoog.platform.audit.AuditService;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * VYB-0913 (F40): sends a {@link ConnectorOperation} through a registered connection and does, the
 * same way for every connector, what each of them would otherwise have to do for itself:
 *
 * <ol>
 *   <li><b>Configuration</b>: read from the registry row ({@link ConnectorConfig}); an unusable one
 *       throws {@link ConnectorNotConfiguredException} before anything is sent or recorded.</li>
 *   <li><b>Idempotency</b>: the operation's key is claimed in the sync log first. A key that already
 *       succeeded is not sent again ({@code ALREADY_DONE}); one being sent right now by another
 *       instance is not sent twice ({@code IN_PROGRESS_ELSEWHERE}). The key also goes to the receiver
 *       as {@code Idempotency-Key}, unchanged on every retry.</li>
 *   <li><b>Authentication</b>: the schemes the connection lists ({@link ConnectorAuth}).</li>
 *   <li><b>Retries with backoff</b>: {@link RetryPolicy}; only what could succeed next time is retried.</li>
 *   <li><b>Sync log</b>: one row per operation with its attempts and outcome, never a payload or a secret.</li>
 *   <li><b>Health</b>: the registry's failure counters are updated, atomically, once per operation (not
 *       per attempt); the connection becomes DEGRADED after three operations in a row fail and an audit
 *       event records that, and the recovery.</li>
 * </ol>
 *
 * <p>Redirects are never followed and a response is read only up to 64 KB, so a receiver can neither
 * bounce the credentials to another host nor exhaust memory. This class is not transactional and
 * does no database work while a request is in flight; its writes are short transactions of their
 * own, so it is safe to call from inside a caller's transaction, though a caller should still not
 * hold one open across a send.
 */
@Component
public class ConnectorExecutor {

    private static final Logger log = LoggerFactory.getLogger(ConnectorExecutor.class);

    static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int ERROR_DETAIL_CHARS = 200;
    /** Longer than the slowest legitimate operation (all attempts and waits), so a live send is never called abandoned. */
    static final Duration STALE_AFTER = Duration.ofMinutes(15);

    /** Waits between attempts; replaced in tests so they do not sleep. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final IntegrationService integrations;
    private final ConnectorSyncLog syncLog;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper json;
    private final MeterRegistry meters;
    private final RetryPolicy policy;
    private final Sleeper sleeper;
    private final DoubleSupplier random;
    private final TransactionTemplate tx;
    private final HttpClient http;

    @Autowired
    public ConnectorExecutor(IntegrationService integrations, ConnectorSyncLog syncLog, JdbcTemplate jdbc,
                             AuditService audit, ObjectMapper json, MeterRegistry meters,
                             PlatformTransactionManager txManager,
                             @Value("${vyoog.connector.max-attempts:4}") int maxAttempts,
                             @Value("${vyoog.connector.initial-delay-ms:500}") long initialDelayMs,
                             @Value("${vyoog.connector.max-delay-ms:30000}") long maxDelayMs,
                             @Value("${vyoog.connector.request-timeout-ms:10000}") long requestTimeoutMs) {
        this(integrations, syncLog, jdbc, audit, json, meters, txManager,
            new RetryPolicy(maxAttempts, Duration.ofMillis(initialDelayMs), Duration.ofMillis(maxDelayMs),
                Duration.ofMillis(requestTimeoutMs)),
            d -> Thread.sleep(d), () -> ThreadLocalRandom.current().nextDouble());
    }

    /** For tests, which supply their own policy, a recording {@link Sleeper} and a fixed random source. Spring uses the other one. */
    public ConnectorExecutor(IntegrationService integrations, ConnectorSyncLog syncLog, JdbcTemplate jdbc, AuditService audit,
                      ObjectMapper json, MeterRegistry meters, PlatformTransactionManager txManager,
                      RetryPolicy policy, Sleeper sleeper, DoubleSupplier random) {
        this.integrations = integrations;
        this.syncLog = syncLog;
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
        this.meters = meters;
        this.policy = policy;
        this.sleeper = sleeper;
        this.random = random;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    private record Attempt(Integer status, String error, Duration retryAfter, boolean retryable) {
        boolean ok() { return status != null && status >= 200 && status < 300; }
    }

    /** @throws ConnectorNotConfiguredException if the connection cannot be used as configured */
    public ConnectorResult execute(ConnectorOperation op) {
        IntegrationConnection conn = integrations.get(op.connectionKey());
        ConnectorConfig config = ConnectorConfig.load(conn, json);
        URI target = target(config, op);

        ConnectorSyncLog.Claim claim = syncLog.claim(op.connectionKey(), op.operation(), op.idempotencyKey(),
            op.body(), STALE_AFTER);
        switch (claim.kind()) {
            case ALREADY_SUCCEEDED -> {
                count(op, "already_done");
                return new ConnectorResult(ConnectorResult.Outcome.ALREADY_DONE, 0, null, null, claim.id());
            }
            case IN_PROGRESS -> {
                count(op, "in_progress_elsewhere");
                return new ConnectorResult(ConnectorResult.Outcome.IN_PROGRESS_ELSEWHERE, 0, null, null, claim.id());
            }
            case CLAIMED -> { }
        }

        int attempts = 0;
        Attempt last = null;
        try {
            while (attempts < policy.maxAttempts()) {
                attempts++;
                last = sendOnce(config, op, target);
                meters.counter("vyoog.connector.attempts", "connection", op.connectionKey()).increment();
                if (last.ok() || !last.retryable() || attempts == policy.maxAttempts()) break;
                try {
                    sleeper.sleep(policy.delayBefore(attempts, last.retryAfter(), random));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    last = new Attempt(null, "interrupted while waiting to retry", null, false);
                    break;
                }
            }
        } catch (RuntimeException e) {
            // Never leave the key claimed: an unexpected failure must still end the log row.
            log.error("[connector] {} {} failed unexpectedly: {}", op.connectionKey(), op.operation(), e.toString());
            last = new Attempt(null, "unexpected error: " + e.getClass().getSimpleName(), null, false);
        }

        boolean ok = last.ok();
        syncLog.finish(claim.id(), ok, attempts, last.status(), ok ? null : last.error());
        recordHealth(op.connectionKey(), ok, ok ? null : last.error());
        count(op, ok ? "succeeded" : "failed");
        return new ConnectorResult(ok ? ConnectorResult.Outcome.SUCCEEDED : ConnectorResult.Outcome.FAILED,
            attempts, last.status(), ok ? null : last.error(), claim.id());
    }

    /** The base URL plus the operation's path, checked to still be on the base URL's host. */
    private static URI target(ConnectorConfig config, ConnectorOperation op) {
        URI base = config.baseUri();
        URI target = URI.create(base + op.path());
        if (!base.getScheme().equalsIgnoreCase(target.getScheme()) || !base.getHost().equalsIgnoreCase(target.getHost())
            || base.getPort() != target.getPort()) {
            throw new IllegalArgumentException("operation path would leave the connection's host");
        }
        return target;
    }

    private Attempt sendOnce(ConnectorConfig config, ConnectorOperation op, URI target) {
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
            .timeout(policy.requestTimeout())
            .header("Content-Type", op.contentType())
            .header("Idempotency-Key", op.idempotencyKey())
            .header("User-Agent", "vyoog-connector")
            .method(op.method(), op.body().length == 0 && op.method().equals("GET")
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(op.body()));
        for (ConnectorAuth auth : config.auth()) auth.apply(request, config, op.body());

        try {
            HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            String detail;
            try (InputStream in = response.body()) {
                detail = new String(in.readNBytes(MAX_RESPONSE_BYTES), StandardCharsets.UTF_8);
            }
            int status = response.statusCode();
            if (status >= 200 && status < 300) return new Attempt(status, null, null, false);
            return new Attempt(status, "HTTP " + status + excerpt(config.redact(detail)), retryAfter(response), policy.retryable(status));
        } catch (java.net.http.HttpTimeoutException e) {
            return new Attempt(null, "timed out after " + policy.requestTimeout().toSeconds() + "s", null, true);
        } catch (IOException e) {
            return new Attempt(null, "could not reach the receiver: " + e.getClass().getSimpleName(), null, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Attempt(null, "interrupted", null, false);
        }
    }

    private static Duration retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").map(v -> {
            try {
                long seconds = Long.parseLong(v.trim());
                return seconds < 0 ? null : Duration.ofSeconds(seconds);
            } catch (NumberFormatException notSeconds) {
                return null; // an HTTP date: ignored, the backoff applies
            }
        }).orElse(null);
    }

    /**
     * A short, single-line, printable excerpt of the receiver's reason. Never a request header, and
     * any of this connection's own secrets the receiver happened to echo back are already removed.
     */
    private static String excerpt(String body) {
        String clean = body.replaceAll("[^\\x20-\\x7E]+", " ").strip();
        if (clean.isEmpty()) return "";
        return ": " + (clean.length() > ERROR_DETAIL_CHARS ? clean.substring(0, ERROR_DETAIL_CHARS) + "…" : clean);
    }

    /**
     * Updates the registry row's counters in one statement and, when the connection crosses into or
     * out of DEGRADED, records an audit event in the same transaction. Success also marks the
     * connection connected and clears its last error, as a successful push always did.
     */
    private void recordHealth(String key, boolean ok, String error) {
        tx.executeWithoutResult(status -> {
            List<Map<String, Object>> rows = ok
                ? jdbc.queryForList("""
                    WITH prev AS (SELECT degraded FROM integration_connection WHERE key = ? FOR UPDATE)
                    UPDATE integration_connection c
                       SET failure_count = 0, degraded = false, connected = true, last_error = NULL
                      FROM prev WHERE c.key = ?
                    RETURNING prev.degraded AS was_degraded, c.degraded AS now_degraded, c.failure_count AS failures
                    """, key, key)
                : jdbc.queryForList("""
                    WITH prev AS (SELECT degraded FROM integration_connection WHERE key = ? FOR UPDATE)
                    UPDATE integration_connection c
                       SET failure_count = c.failure_count + 1, last_error = ?, last_error_at = clock_timestamp(),
                           degraded = (c.failure_count + 1 >= ?)
                      FROM prev WHERE c.key = ?
                    RETURNING prev.degraded AS was_degraded, c.degraded AS now_degraded, c.failure_count AS failures
                    """, key, error, IntegrationConnection.DEGRADE_AFTER_FAILURES, key);
            if (rows.isEmpty()) return;
            boolean was = (Boolean) rows.get(0).get("was_degraded");
            boolean now = (Boolean) rows.get(0).get("now_degraded");
            if (!was && now) {
                audit.recordSystem("connector.degraded", "CONNECTOR", null, Map.of(
                    "connection", key, "consecutiveFailures", rows.get(0).get("failures"), "lastError", String.valueOf(error)));
                log.warn("[connector] {} is degraded after {} failed operations in a row: {}", key, rows.get(0).get("failures"), error);
            } else if (was && !now) {
                audit.recordSystem("connector.recovered", "CONNECTOR", null, Map.of("connection", key));
                log.info("[connector] {} recovered", key);
            }
        });
    }

    private void count(ConnectorOperation op, String outcome) {
        meters.counter("vyoog.connector.operations", "connection", op.connectionKey(), "outcome", outcome).increment();
    }

}
