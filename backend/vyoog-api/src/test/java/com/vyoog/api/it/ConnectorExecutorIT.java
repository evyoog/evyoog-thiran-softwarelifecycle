package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vyoog.integration.IntegrationConnection;
import com.vyoog.integration.IntegrationService;
import com.vyoog.integration.WebhookSignatureVerifier;
import com.vyoog.integration.connector.ConnectorExecutor;
import com.vyoog.integration.connector.ConnectorHealth;
import com.vyoog.integration.connector.ConnectorHealthService;
import com.vyoog.integration.connector.ConnectorNotConfiguredException;
import com.vyoog.integration.connector.ConnectorOperation;
import com.vyoog.integration.connector.ConnectorRegistry;
import com.vyoog.integration.connector.ConnectorResult;
import com.vyoog.integration.connector.ConnectorSyncLog;
import com.vyoog.integration.connector.RetryPolicy;
import com.vyoog.platform.audit.AuditService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * VYB-0913 (F40): the connector framework against a real PostgreSQL and real HTTP. Every request goes
 * over a socket to a stub server in this JVM; nothing in the engine is mocked. The only substitutions
 * are the wait between attempts (recorded, not slept) and the jitter (fixed), so the tests are fast
 * and exact about what the backoff would have been.
 */
class ConnectorExecutorIT extends IntegrationTestBase {

    @Autowired IntegrationService integrations;
    @Autowired ConnectorSyncLog syncLog;
    @Autowired AuditService audit;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ConnectorHealthService healthService;

    private static final RetryPolicy POLICY =
        new RetryPolicy(3, Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofSeconds(2));

    /** One request the stub received. */
    record Received(String method, String path, Map<String, String> headers, String body) {
        String header(String name) { return headers.get(name.toLowerCase()); }
    }

    private HttpServer server;
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private volatile Function<Received, Object[]> responder = r -> new Object[] {200, ""};
    private final List<Duration> waits = Collections.synchronizedList(new ArrayList<>());
    private ConnectorExecutor executor;
    private String key;

    @BeforeEach
    void startStubAndConnection() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        executor = new ConnectorExecutor(integrations, new ConnectorRegistry(List.of()), syncLog, jdbc, audit, json, new SimpleMeterRegistry(), transactions,
            POLICY, waits::add, () -> 0.5);
        key = unique("conn");
        integrations.create(key, "connector IT", IntegrationConnection.Direction.OUTBOUND);
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        Map<String, String> headers = new java.util.HashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Received r = new Received(ex.getRequestMethod(), ex.getRequestURI().getPath(), headers, body);
        received.add(r);
        Object[] answer = responder.apply(r);
        int status = (Integer) answer[0];
        byte[] payload = ((String) answer[1]).getBytes(StandardCharsets.UTF_8);
        if (answer.length > 2) ex.getResponseHeaders().add((String) answer[2], (String) answer[3]);
        ex.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
        if (payload.length > 0) ex.getResponseBody().write(payload);
        ex.close();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void configure(String authJson, String secret) {
        integrations.setConfig(key, "{\"baseUrl\":\"" + baseUrl() + "/api\",\"auth\":" + authJson + "}");
        if (secret != null) integrations.setConnected(key, false, secret);
    }

    private void configureNoAuth() {
        configure("[\"NONE\"]", null);
    }

    private ConnectorOperation op(String idempotencyKey) {
        return ConnectorOperation.postJson(key, "function.upsert", idempotencyKey, "/functions", "{\"name\":\"login\"}");
    }

    private IntegrationConnection connection() {
        return integrations.get(key);
    }

    private int auditCount(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = ? AND after ->> 'connection' = ?",
            Integer.class, action, key);
    }

    // ---------------------------------------------------------------- the happy path

    @Test
    void VYB0913_AC1_aSuccessfulOperationIsSentOnceAndRecordedAndTheConnectionIsConnected() {
        configureNoAuth();
        ConnectorResult result = executor.execute(op("fn:1:rev1"));

        assertThat(result.outcome()).isEqualTo(ConnectorResult.Outcome.SUCCEEDED);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).method()).isEqualTo("POST");
        assertThat(received.get(0).path()).isEqualTo("/api/functions");
        assertThat(received.get(0).header("Content-Type")).isEqualTo("application/json");
        assertThat(received.get(0).body()).isEqualTo("{\"name\":\"login\"}");
        assertThat(connection().isConnected()).isTrue();
        assertThat(waits).isEmpty();
    }

    // ---------------------------------------------------------------- retries, backoff, idempotency key

    @Test
    void VYB0913_AC2_aTransientFailureIsRetriedWithBackoffAndTheSameIdempotencyKeyEveryTime() {
        configureNoAuth();
        AtomicInteger calls = new AtomicInteger();
        responder = r -> calls.incrementAndGet() < 3 ? new Object[] {503, "overloaded"} : new Object[] {200, ""};

        ConnectorResult result = executor.execute(op("fn:2:rev1"));

        assertThat(result.outcome()).isEqualTo(ConnectorResult.Outcome.SUCCEEDED);
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(received).extracting(r -> r.header("Idempotency-Key")).containsOnly("fn:2:rev1").hasSize(3);
        // jitter fixed at 0.5: half of the step plus half of the step again, the step doubling: 150ms, 300ms
        assertThat(waits).containsExactly(Duration.ofMillis(150), Duration.ofMillis(300));
        assertThat(syncLog.recent(key, 10)).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("SUCCEEDED");
            assertThat(row.attempts()).isEqualTo(3);
        });
    }

    @Test
    void VYB0913_AC2_aDefiniteRefusalIsNotRetried() {
        configureNoAuth();
        responder = r -> new Object[] {400, "name is required"};

        ConnectorResult result = executor.execute(op("fn:3:rev1"));

        assertThat(result.outcome()).isEqualTo(ConnectorResult.Outcome.FAILED);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.httpStatus()).isEqualTo(400);
        assertThat(result.error()).isEqualTo("HTTP 400: name is required");
        assertThat(received).hasSize(1);
        assertThat(waits).isEmpty();
    }

    @Test
    void VYB0913_AC2_everyAttemptFailingEndsFailedAfterTheConfiguredNumberOfAttempts() {
        configureNoAuth();
        responder = r -> new Object[] {500, "boom"};

        ConnectorResult result = executor.execute(op("fn:4:rev1"));

        assertThat(result.outcome()).isEqualTo(ConnectorResult.Outcome.FAILED);
        assertThat(result.attempts()).isEqualTo(POLICY.maxAttempts());
        assertThat(received).hasSize(POLICY.maxAttempts());
        assertThat(waits).hasSize(POLICY.maxAttempts() - 1);
    }

    @Test
    void VYB0913_AC2_aReceiversRetryAfterIsHonouredWhenItIsLongerThanTheBackoff() {
        configureNoAuth();
        AtomicInteger calls = new AtomicInteger();
        responder = r -> calls.incrementAndGet() == 1 ? new Object[] {429, "slow down", "Retry-After", "3"} : new Object[] {200, ""};

        executor.execute(op("fn:5:rev1"));

        assertThat(waits).containsExactly(Duration.ofSeconds(3));
    }

    @Test
    void VYB0913_AC2_aReceiverThatCannotBeReachedIsRetriedThenReportedWithoutStackTraceOrHost() {
        configureNoAuth();
        server.stop(0); // connection refused from here on

        ConnectorResult result = executor.execute(op("fn:6:rev1"));

        assertThat(result.outcome()).isEqualTo(ConnectorResult.Outcome.FAILED);
        assertThat(result.attempts()).isEqualTo(POLICY.maxAttempts());
        assertThat(result.httpStatus()).isNull();
        assertThat(result.error()).startsWith("could not reach the receiver").doesNotContain("127.0.0.1");
    }

    @Test
    void VYB0913_AC2_aReceiverThatAnswersTooSlowlyIsATimeoutAndIsRetried() {
        configureNoAuth();
        executor = new ConnectorExecutor(integrations, new ConnectorRegistry(List.of()), syncLog, jdbc, audit, json, new SimpleMeterRegistry(), transactions,
            new RetryPolicy(2, Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofMillis(300)), waits::add, () -> 0.5);
        responder = r -> { try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return new Object[] {200, ""}; };

        ConnectorResult result = executor.execute(op("fn:7:rev1"));

        assertThat(result.outcome()).isEqualTo(ConnectorResult.Outcome.FAILED);
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.error()).startsWith("timed out");
    }

    @Test
    void VYB0913_AC2_aRedirectIsNotFollowedSoCredentialsCannotBeBouncedToAnotherHost() {
        configure("[\"BEARER\"]", null);
        integrations.setConfig(key, "{\"baseUrl\":\"" + baseUrl() + "\",\"auth\":[\"BEARER\"],\"bearerToken\":\"TOKEN-1\"}");
        responder = r -> new Object[] {302, "", "Location", "http://127.0.0.1:1/elsewhere"};

        ConnectorResult result = executor.execute(op("fn:8:rev1"));

        assertThat(result.outcome()).isEqualTo(ConnectorResult.Outcome.FAILED);
        assertThat(result.httpStatus()).isEqualTo(302);
        assertThat(result.attempts()).as("a redirect is a definite answer, not retried").isEqualTo(1);
        assertThat(received).hasSize(1);
    }

    @Test
    void VYB0913_AC2_aHugeOrHostileErrorBodyIsReducedToAShortPrintableExcerpt() {
        configureNoAuth();
        String hostile = "line one\r\nSet-Cookie: x=1\u0000\u0007 " + "A".repeat(500_000);
        responder = r -> new Object[] {400, hostile};

        ConnectorResult result = executor.execute(op("fn:9:rev1"));

        assertThat(result.error()).hasSizeLessThan(230).doesNotContain("\r", "\n", "\u0000");
        assertThat(result.error()).startsWith("HTTP 400: line one Set-Cookie: x=1");
    }

    // ---------------------------------------------------------------- authentication

    @Test
    void VYB0913_AC3_theConfiguredSchemesAreAppliedAndASignatureVerifiesOverTheExactBody() {
        integrations.setConfig(key, "{\"baseUrl\":\"" + baseUrl() + "\",\"auth\":[\"HMAC_SIGNATURE\",\"API_KEY\",\"BEARER\"],"
            + "\"apiKey\":\"KEY-1\",\"apiKeyHeader\":\"X-Planner-Key\",\"bearerToken\":\"TOKEN-1\"}");
        integrations.setConnected(key, false, "SHARED-1");

        executor.execute(op("fn:10:rev1"));

        Received r = received.get(0);
        assertThat(r.header("X-Planner-Key")).isEqualTo("KEY-1");
        assertThat(r.header("Authorization")).isEqualTo("Bearer TOKEN-1");
        assertThat(WebhookSignatureVerifier.verify("SHARED-1", r.body(), r.header("X-Vyoog-Signature"))).isTrue();
        assertThat(WebhookSignatureVerifier.verify("another-secret", r.body(), r.header("X-Vyoog-Signature"))).isFalse();
    }

    @Test
    void VYB0913_AC3_withAuthNoneNoCredentialHeaderIsSent() {
        configureNoAuth();
        executor.execute(op("fn:11:rev1"));
        Received r = received.get(0);
        assertThat(r.header("Authorization")).isNull();
        assertThat(r.header("X-API-Key")).isNull();
        assertThat(r.header("X-Vyoog-Signature")).isNull();
    }

    @Test
    void VYB0913_AC3_aSecretTheReceiverEchoesBackNeverReachesTheSyncLog() {
        integrations.setConfig(key, "{\"baseUrl\":\"" + baseUrl() + "\",\"auth\":[\"API_KEY\"],\"apiKey\":\"SUPER-SECRET-KEY\"}");
        responder = r -> new Object[] {400, "invalid key SUPER-SECRET-KEY presented"};

        ConnectorResult result = executor.execute(op("fn:12:rev1"));

        assertThat(result.error()).isEqualTo("HTTP 400: invalid key [redacted] presented");
        assertThat(syncLog.recent(key, 5).get(0).error()).doesNotContain("SUPER-SECRET-KEY");
        assertThat(connection().getLastError()).doesNotContain("SUPER-SECRET-KEY");
    }

    @Test
    void VYB0913_AC3_anUnconfiguredConnectionSendsNothingRecordsNothingAndIsNotCountedAsAFailure() {
        // no configuration at all
        assertThatThrownBy(() -> executor.execute(op("fn:13:rev1"))).isInstanceOf(ConnectorNotConfiguredException.class)
            .hasMessageContaining("no configuration");
        // plain http to a remote host
        integrations.setConfig(key, "{\"baseUrl\":\"http://planner.example.com\",\"auth\":[\"NONE\"]}");
        assertThatThrownBy(() -> executor.execute(op("fn:13:rev2"))).isInstanceOf(ConnectorNotConfiguredException.class)
            .hasMessageContaining("https");

        assertThat(received).isEmpty();
        assertThat(syncLog.recent(key, 5)).isEmpty();
        assertThat(connection().getFailureCount()).isZero();
        assertThat(connection().isDegraded()).isFalse();
    }

    // ---------------------------------------------------------------- idempotency across calls and instances

    @Test
    void VYB0913_AC2_anOperationThatAlreadySucceededIsNotSentAgain() {
        configureNoAuth();
        executor.execute(op("fn:14:rev1"));

        ConnectorResult again = executor.execute(op("fn:14:rev1"));

        assertThat(again.outcome()).isEqualTo(ConnectorResult.Outcome.ALREADY_DONE);
        assertThat(again.succeeded()).isTrue();
        assertThat(again.attempts()).isZero();
        assertThat(received).hasSize(1);
    }

    @Test
    void VYB0913_AC2_aFailedOperationMayBeRetriedLaterUnderTheSameKey() {
        configureNoAuth();
        responder = r -> new Object[] {400, "no"};
        assertThat(executor.execute(op("fn:15:rev1")).outcome()).isEqualTo(ConnectorResult.Outcome.FAILED);

        responder = r -> new Object[] {200, ""};
        assertThat(executor.execute(op("fn:15:rev1")).outcome()).isEqualTo(ConnectorResult.Outcome.SUCCEEDED);

        assertThat(syncLog.recent(key, 5)).extracting(ConnectorSyncLog.Entry::status).containsExactlyInAnyOrder("FAILED", "SUCCEEDED");
    }

    @Test
    void VYB0913_AC2_exactlyOneOfManyInstancesRacingOnTheSameKeySendsIt() throws Exception {
        configureNoAuth();
        responder = r -> { try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return new Object[] {200, ""}; };
        int contenders = 10;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<ConnectorResult>> results = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                results.add(pool.submit(() -> { start.await(); return executor.execute(op("fn:16:rev1")); }));
            }
            start.countDown();
            List<ConnectorResult.Outcome> outcomes = new ArrayList<>();
            for (Future<ConnectorResult> f : results) outcomes.add(f.get(30, TimeUnit.SECONDS).outcome());

            assertThat(received).as("the receiver saw it once").hasSize(1);
            assertThat(outcomes).filteredOn(o -> o == ConnectorResult.Outcome.SUCCEEDED).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o != ConnectorResult.Outcome.SUCCEEDED).hasSize(contenders - 1)
                .isSubsetOf(ConnectorResult.Outcome.IN_PROGRESS_ELSEWHERE, ConnectorResult.Outcome.ALREADY_DONE);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void VYB0913_AC2_aSendLeftInProgressByAnInstanceThatDiedIsAbandonedAndDoesNotBlockTheKeyForever() {
        configureNoAuth();
        jdbc.update("""
            INSERT INTO connector_sync_log (connection_key, operation, idempotency_key, status, payload_bytes, payload_sha256, started_at)
            VALUES (?, 'function.upsert', 'fn:17:rev1', 'IN_PROGRESS', 0, 'x', clock_timestamp() - interval '20 minutes')""", key);

        ConnectorResult result = executor.execute(op("fn:17:rev1"));

        assertThat(result.outcome()).isEqualTo(ConnectorResult.Outcome.SUCCEEDED);
        assertThat(syncLog.recent(key, 5)).extracting(ConnectorSyncLog.Entry::status).containsExactlyInAnyOrder("FAILED", "SUCCEEDED");
        assertThat(syncLog.recent(key, 5)).filteredOn(e -> e.status().equals("FAILED")).singleElement()
            .satisfies(e -> assertThat(e.error()).contains("abandoned"));
    }

    @Test
    void VYB0913_AC2_aSendThatIsStillRecentlyInProgressIsNotSentASecondTime() {
        configureNoAuth();
        jdbc.update("""
            INSERT INTO connector_sync_log (connection_key, operation, idempotency_key, status, payload_bytes, payload_sha256)
            VALUES (?, 'function.upsert', 'fn:18:rev1', 'IN_PROGRESS', 0, 'x')""", key);

        ConnectorResult result = executor.execute(op("fn:18:rev1"));

        assertThat(result.outcome()).isEqualTo(ConnectorResult.Outcome.IN_PROGRESS_ELSEWHERE);
        assertThat(received).isEmpty();
    }

    // ---------------------------------------------------------------- the sync log

    @Test
    void VYB0913_AC4_theSyncLogRecordsWhatHappenedWithoutThePayloadOrAnySecret() {
        integrations.setConfig(key, "{\"baseUrl\":\"" + baseUrl() + "\",\"auth\":[\"API_KEY\"],\"apiKey\":\"LOGSECRET\"}");
        ConnectorOperation operation = ConnectorOperation.postJson(key, "function.upsert", "fn:19:rev1", "/functions",
            "{\"name\":\"a payload that must not be stored\"}");

        executor.execute(operation);

        ConnectorSyncLog.Entry row = syncLog.recent(key, 1).get(0);
        assertThat(row.operation()).isEqualTo("function.upsert");
        assertThat(row.idempotencyKey()).isEqualTo("fn:19:rev1");
        assertThat(row.payloadBytes()).isEqualTo(operation.body().length);
        assertThat(row.payloadSha256()).hasSize(64);
        assertThat(row.startedAt()).isNotNull();
        assertThat(row.finishedAt()).isAfterOrEqualTo(row.startedAt());
        String everything = jdbc.queryForObject("SELECT row_to_json(l)::text FROM connector_sync_log l WHERE id = ?", String.class, row.id());
        assertThat(everything).doesNotContain("LOGSECRET", "must not be stored");
    }

    @Test
    void VYB0913_AC4_theLogIsWrittenInItsOwnTransactionSoACallersRollbackDoesNotEraseIt() {
        configureNoAuth();
        TransactionTemplate outer = new TransactionTemplate(transactions);
        outer.executeWithoutResult(status -> {
            executor.execute(op("fn:20:rev1"));
            status.setRollbackOnly();
        });

        assertThat(syncLog.recent(key, 5)).hasSize(1);
        assertThat(connection().isConnected()).isTrue();
    }

    @Test
    void VYB0913_AC2_theClaimIsVisibleToOtherConnectionsWhileTheRequestIsInFlight() {
        configureNoAuth();
        List<String> seenDuringRequest = Collections.synchronizedList(new ArrayList<>());
        responder = r -> {
            // the handler runs on another thread and so another database connection: it sees only what is committed
            seenDuringRequest.add(jdbc.queryForObject(
                "SELECT status FROM connector_sync_log WHERE connection_key = ? AND idempotency_key = 'fn:21:rev1'", String.class, key));
            return new Object[] {200, ""};
        };
        new TransactionTemplate(transactions).executeWithoutResult(status -> executor.execute(op("fn:21:rev1")));

        assertThat(seenDuringRequest).containsExactly("IN_PROGRESS");
    }

    // ---------------------------------------------------------------- health

    @Test
    void VYB0913_AC5_threeFailedOperationsInARowDegradeTheConnectionOnceAndASuccessRecoversIt() {
        configureNoAuth();
        responder = r -> new Object[] {400, "no"};

        executor.execute(op("fn:22:a"));
        executor.execute(op("fn:22:b"));
        assertThat(connection().isDegraded()).as("two failures are not yet degraded").isFalse();
        assertThat(healthService.health(key).state()).isEqualTo(ConnectorHealth.State.NOT_CONNECTED);
        executor.execute(op("fn:22:c"));

        assertThat(connection().isDegraded()).isTrue();
        assertThat(connection().getFailureCount()).isEqualTo(3);
        assertThat(auditCount("connector.degraded")).isEqualTo(1);
        ConnectorHealth degraded = healthService.health(key);
        assertThat(degraded.state()).isEqualTo(ConnectorHealth.State.DEGRADED);
        assertThat(degraded.consecutiveFailures()).isEqualTo(3);
        assertThat(degraded.lastError()).isEqualTo("HTTP 400: no");
        assertThat(degraded.lastErrorAt()).isNotNull();

        executor.execute(op("fn:22:d"));
        assertThat(auditCount("connector.degraded")).as("a fourth failure does not announce it again").isEqualTo(1);

        responder = r -> new Object[] {200, ""};
        executor.execute(op("fn:22:e"));

        assertThat(connection().isDegraded()).isFalse();
        assertThat(connection().getFailureCount()).isZero();
        assertThat(auditCount("connector.recovered")).isEqualTo(1);
        ConnectorHealth healthy = healthService.health(key);
        assertThat(healthy.state()).isEqualTo(ConnectorHealth.State.HEALTHY);
        assertThat(healthy.lastSuccessAt()).isNotNull();
        assertThat(healthy.lastError()).isNull();
    }

    @Test
    void VYB0913_AC5_failuresAreCountedPerOperationNotPerAttempt() {
        configureNoAuth();
        responder = r -> new Object[] {503, ""};

        executor.execute(op("fn:23:a")); // three attempts, one failed operation

        assertThat(received).hasSize(POLICY.maxAttempts());
        assertThat(connection().getFailureCount()).isEqualTo(1);
        assertThat(connection().isDegraded()).isFalse();
    }

    @Test
    void VYB0913_AC5_aConnectionNeverUsedOrNotConfiguredReportsNotConnectedWithTheReason() {
        ConnectorHealth fresh = healthService.health(key);
        assertThat(fresh.state()).isEqualTo(ConnectorHealth.State.NOT_CONNECTED);
        assertThat(fresh.notConfiguredReason()).contains("no configuration");
        assertThat(fresh.lastSuccessAt()).isNull();

        configureNoAuth();
        ConnectorHealth configured = healthService.health(key);
        assertThat(configured.state()).as("configured, but nothing has succeeded yet").isEqualTo(ConnectorHealth.State.NOT_CONNECTED);
        assertThat(configured.notConfiguredReason()).isNull();
        assertThat(healthService.all()).extracting(ConnectorHealth::connectionKey).contains(key, "planning");
    }

    @Test
    void VYB0913_AC5_theMetricsCountOperationsByOutcome() {
        configureNoAuth();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        executor = new ConnectorExecutor(integrations, new ConnectorRegistry(List.of()), syncLog, jdbc, audit, json, meters, transactions, POLICY, waits::add, () -> 0.5);
        executor.execute(op("fn:24:a"));
        executor.execute(op("fn:24:a"));
        responder = r -> new Object[] {400, ""};
        executor.execute(op("fn:24:b"));

        assertThat(meters.counter("vyoog.connector.operations", "connection", key, "outcome", "succeeded").count()).isEqualTo(1.0);
        assertThat(meters.counter("vyoog.connector.operations", "connection", key, "outcome", "already_done").count()).isEqualTo(1.0);
        assertThat(meters.counter("vyoog.connector.operations", "connection", key, "outcome", "failed").count()).isEqualTo(1.0);
        assertThat(meters.counter("vyoog.connector.attempts", "connection", key).count()).isEqualTo(2.0);
    }
}
