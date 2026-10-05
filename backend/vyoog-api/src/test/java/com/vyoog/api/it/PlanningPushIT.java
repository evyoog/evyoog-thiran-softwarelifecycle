package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vyoog.brief.BriefPushService;
import com.vyoog.integration.IntegrationService;
import com.vyoog.integration.WebhookSignatureVerifier;
import com.vyoog.integration.connector.ConnectorHealth;
import com.vyoog.integration.connector.ConnectorHealthService;
import com.vyoog.integration.connector.ConnectorRegistry;
import com.vyoog.integration.connector.ConnectorSyncLog;
import com.vyoog.signals.SignalsExportService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * VYB-0916 (F16, F40): the two pushes to the "planning" connection, {@code BriefPushService} and
 * {@code SignalsExportService}, now go through the connector framework. Real services, real PostgreSQL and a
 * real HTTP server in this JVM; the connection is configured the way the Administration screen configures it.
 * The "planning" row is shared (it is seeded by a migration), so each test restores it afterwards.
 */
class PlanningPushIT extends IntegrationTestBase {

    @Autowired BriefPushService briefPush;
    @Autowired SignalsExportService signalsPush;
    @Autowired IntegrationService integrations;
    @Autowired ConnectorSyncLog syncLog;
    @Autowired ConnectorHealthService health;
    @Autowired ConnectorRegistry registry;

    record Received(String method, String path, Map<String, String> headers, String body) {
        String header(String name) { return headers.get(name.toLowerCase()); }
    }

    private static final String SECRET = "planning-shared-secret";

    private HttpServer server;
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private volatile Function<Received, Object[]> responder = r -> new Object[] {200, ""};
    private Map<String, Object> originalRow;

    @BeforeEach
    void startStubAndRememberTheSeededRow() throws IOException {
        originalRow = jdbc.queryForMap("SELECT config::text AS config, webhook_secret, connected, failure_count, degraded, last_error, last_error_at "
            + "FROM integration_connection WHERE key = 'planning'");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        jdbc.update("UPDATE integration_connection SET failure_count = 0, degraded = false, last_error = NULL WHERE key = 'planning'");
    }

    @AfterEach
    void stopStubAndRestoreTheSeededRow() {
        server.stop(0);
        jdbc.update("""
            UPDATE integration_connection SET config = ?::jsonb, webhook_secret = ?, connected = ?, failure_count = ?,
                   degraded = ?, last_error = ?, last_error_at = ? WHERE key = 'planning'""",
            originalRow.get("config"), originalRow.get("webhook_secret"), originalRow.get("connected"),
            originalRow.get("failure_count"), originalRow.get("degraded"), originalRow.get("last_error"), originalRow.get("last_error_at"));
    }

    private void handle(HttpExchange ex) throws IOException {
        Map<String, String> headers = new HashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Received r = new Received(ex.getRequestMethod(), ex.getRequestURI().getPath(), headers, body);
        received.add(r);
        Object[] answer = responder.apply(r);
        byte[] payload = ((String) answer[1]).getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders((Integer) answer[0], payload.length == 0 ? -1 : payload.length);
        if (payload.length > 0) ex.getResponseBody().write(payload);
        ex.close();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    /** What the Administration screen saves: a JSON object of exactly these three keys, "" for what was left empty. */
    private void configureLikeTheAdminScreen(String pushUrl, String apiKey, String customerName, String secret) {
        String config = "{\"pushUrl\":\"%s\",\"apiKey\":\"%s\",\"customerName\":\"%s\"}".formatted(pushUrl, apiKey, customerName);
        integrations.setConfig("planning", config);
        integrations.setConnected("planning", false, secret);
    }

    private UUID newBrief(Portfolio p, UUID actor) {
        return jdbc.queryForObject("""
            INSERT INTO brief (application_id, target, developer_id, content, generated_by)
            VALUES (?, 'HUMAN', ?, ?, ?) RETURNING id""", UUID.class, p.applicationId(), actor, "# Brief\n\nBuild the thing.", actor);
    }

    private List<ConnectorSyncLog.Entry> planningLog() {
        return syncLog.recent("planning", 50);
    }

    // ---------------------------------------------------------------- briefs

    @Test
    void VYB0916_AC1_aBriefIsPushedInTheOldSignedMultipartFormatWithTheKeyAndTheSignature() {
        UUID actor = newUser("push");
        Portfolio p = newPortfolio();
        UUID brief = newBrief(p, actor);
        configureLikeTheAdminScreen(url("/push"), "key-123", "Acme Corp", SECRET);

        BriefPushService.PushResult result = briefPush.push(brief, actor);

        assertThat(result.success()).isTrue();
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(received).hasSize(1);
        Received r = received.get(0);
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.path()).isEqualTo("/push");
        assertThat(r.header("Content-Type")).startsWith("multipart/form-data; boundary=----VyoogBoundary");
        assertThat(r.header("X-API-Key")).isEqualTo("key-123");
        assertThat(WebhookSignatureVerifier.verify(SECRET, r.body(), r.header("X-Vyoog-Signature")))
            .as("hex HMAC-SHA256 of the exact body, as the old push signed it").isTrue();
        assertThat(r.body()).contains("name=\"customerName\"").contains("\r\n\r\nAcme Corp\r\n")
            .contains("name=\"level\"").contains("APPLICATION").contains("# Brief").contains("Build the thing.");
        assertThat(r.header("Idempotency-Key")).startsWith("brief:" + brief + ":");
    }

    @Test
    void VYB0916_AC1_withNoApiKeyTheHeaderIsOmittedAndTheScreensBlankFieldsDoNotRefuse() {
        UUID actor = newUser("push");
        UUID brief = newBrief(newPortfolio(), actor);
        configureLikeTheAdminScreen(url("/push"), "", "", SECRET);

        assertThat(briefPush.push(brief, actor).success()).isTrue();

        assertThat(received.get(0).header("X-API-Key")).isNull();
        assertThat(received.get(0).header("X-Vyoog-Signature")).isNotBlank();
        assertThat(received.get(0).body()).contains("\r\n\r\nvyoog\r\n");
    }

    @Test
    void VYB0916_AC1_theUrlIsSentExactlyAsConfiguredIncludingATrailingSlash() {
        UUID actor = newUser("push");
        UUID brief = newBrief(newPortfolio(), actor);
        configureLikeTheAdminScreen(url("/hook/"), "", "", SECRET);

        briefPush.push(brief, actor);

        assertThat(received.get(0).path()).isEqualTo("/hook/");
    }

    @Test
    void VYB0916_AC5_aTransientFailureIsRetriedAndThePushThenSucceedsUnderOneIdempotencyKey() {
        UUID actor = newUser("push");
        UUID brief = newBrief(newPortfolio(), actor);
        configureLikeTheAdminScreen(url("/push"), "key", "", SECRET);
        AtomicInteger calls = new AtomicInteger();
        responder = r -> calls.incrementAndGet() < 3 ? new Object[] {503, "overloaded"} : new Object[] {200, ""};

        BriefPushService.PushResult result = briefPush.push(brief, actor);

        assertThat(result.success()).isTrue();
        assertThat(received).hasSize(3);
        assertThat(received).extracting(r -> r.header("Idempotency-Key")).containsOnly(received.get(0).header("Idempotency-Key"));
        assertThat(planningLog().get(0).attempts()).isEqualTo(3);
    }

    @Test
    void VYB0916_AC5_aPushTheReceiverRefusesIsReportedAndCountsAgainstTheConnectionsHealth() {
        UUID actor = newUser("push");
        UUID brief = newBrief(newPortfolio(), actor);
        configureLikeTheAdminScreen(url("/push"), "bad-key", "", SECRET);
        responder = r -> new Object[] {401, "unknown key bad-key"};

        BriefPushService.PushResult result = briefPush.push(brief, actor);

        assertThat(result.success()).isFalse();
        assertThat(result.statusCode()).isEqualTo(401);
        assertThat(result.error()).isEqualTo("HTTP 401: unknown key [redacted]");
        assertThat(received).as("a 401 is a definite answer, not retried").hasSize(1);
        assertThat(integrations.get("planning").getFailureCount()).isEqualTo(1);
        assertThat(integrations.get("planning").getLastError()).doesNotContain("bad-key");
        assertThat(auditCount(brief, "brief.pushed")).isEqualTo(1);
    }

    @Test
    void VYB0916_AC5_aSuccessfulPushIsInTheSyncLogAndLeavesTheConnectionConnectedAndHealthy() {
        UUID actor = newUser("push");
        UUID brief = newBrief(newPortfolio(), actor);
        configureLikeTheAdminScreen(url("/push"), "", "", SECRET);

        briefPush.push(brief, actor);

        ConnectorSyncLog.Entry row = planningLog().get(0);
        assertThat(row.operation()).isEqualTo("brief.push");
        assertThat(row.status()).isEqualTo("SUCCEEDED");
        assertThat(row.httpStatus()).isEqualTo(200);
        assertThat(row.idempotencyKey()).startsWith("brief:" + brief + ":");
        assertThat(integrations.get("planning").isConnected()).isTrue();
        ConnectorHealth state = health.health("planning");
        assertThat(state.state()).isEqualTo(ConnectorHealth.State.HEALTHY);
        assertThat(state.lastSuccessAt()).isNotNull();
        assertThat(auditCount(brief, "brief.pushed")).isEqualTo(1);
    }

    @Test
    void VYB0916_AC1_pushingTheSameBriefAgainSendsAgainAsItAlwaysHas() {
        UUID actor = newUser("push");
        UUID brief = newBrief(newPortfolio(), actor);
        configureLikeTheAdminScreen(url("/push"), "", "", SECRET);

        briefPush.push(brief, actor);
        briefPush.push(brief, actor);

        assertThat(received).hasSize(2);
        assertThat(planningLog()).filteredOn(e -> e.idempotencyKey().startsWith("brief:" + brief)).hasSize(2)
            .extracting(ConnectorSyncLog.Entry::status).containsOnly("SUCCEEDED");
    }

    // ---------------------------------------------------------------- refusals

    @Test
    void VYB0916_AC3_anUnconfiguredConnectionRefusesInTheOldWordsAndSendsNothing() {
        UUID actor = newUser("push");
        UUID brief = newBrief(newPortfolio(), actor);
        jdbc.update("UPDATE integration_connection SET config = NULL, webhook_secret = NULL WHERE key = 'planning'");

        assertThatThrownBy(() -> briefPush.push(brief, actor)).isInstanceOf(IllegalStateException.class)
            .hasMessage("No push URL configured for \"planning\" — set one in Administration first.");

        integrations.setConfig("planning", "{\"pushUrl\":\"" + url("/push") + "\"}");
        assertThatThrownBy(() -> briefPush.push(brief, actor)).isInstanceOf(IllegalStateException.class)
            .hasMessage("No shared secret configured for \"planning\" — the receiver couldn't verify this push anyway.");

        assertThat(received).isEmpty();
        assertThat(integrations.get("planning").getFailureCount()).as("not configured is not a failure").isZero();
        assertThat(auditCount(brief, "brief.pushed")).isZero();
    }

    @Test
    void VYB0916_AC3_aPlainHttpUrlToARemoteHostIsRefusedByNameNotSentInTheClear() {
        UUID actor = newUser("push");
        UUID brief = newBrief(newPortfolio(), actor);
        configureLikeTheAdminScreen("http://planning.example.com/hook", "key", "", SECRET);

        assertThatThrownBy(() -> briefPush.push(brief, actor)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("planning").hasMessageContaining("must be https");
    }

    // ---------------------------------------------------------------- signals

    @Test
    void VYB0916_AC1_theScopeSignalsArePushedAsSignedJsonOnTheSignalsOperation() {
        UUID actor = newUser("push");
        Portfolio p = newPortfolio();
        configureLikeTheAdminScreen(url("/signals"), "k", "", SECRET);

        SignalsExportService.PushResult result = signalsPush.pushToDeliveryTool(List.of(p.capabilityId()), actor);

        assertThat(result.success()).isTrue();
        Received r = received.get(0);
        assertThat(r.path()).isEqualTo("/signals");
        assertThat(r.header("Content-Type")).isEqualTo("application/json");
        assertThat(r.header("X-API-Key")).isEqualTo("k");
        assertThat(WebhookSignatureVerifier.verify(SECRET, r.body(), r.header("X-Vyoog-Signature"))).isTrue();
        assertThat(r.body()).contains("\"capabilityIds\":[\"" + p.capabilityId() + "\"]").contains("\"signals\":{")
            .contains("requirementCount").contains("novelty");
        assertThat(planningLog().get(0).operation()).isEqualTo("signals.push");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'signals.pushed' AND actor_id = ?", Integer.class, actor))
            .isEqualTo(1);
    }

    @Test
    void VYB0916_AC3_signalsRefuseInTheSameOldWordsWhenNothingIsConfigured() {
        jdbc.update("UPDATE integration_connection SET config = NULL WHERE key = 'planning'");
        assertThatThrownBy(() -> signalsPush.pushToDeliveryTool(List.of(), newUser("push")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("No push URL configured");
    }

    // ---------------------------------------------------------------- the connection as a connector

    @Test
    void VYB0916_AC1_thePlanningConnectionIsRegisteredAsAConnectorWithBothOperations() {
        assertThat(registry.forConnection("planning")).isPresent().get().satisfies(c ->
            assertThat(c.operations()).containsExactlyInAnyOrder("brief.push", "signals.push"));
    }

    @Test
    void VYB0916_AC5_theHealthStateUsesTheOldConfigurationShapeToo() {
        jdbc.update("UPDATE integration_connection SET config = NULL, webhook_secret = NULL, connected = false WHERE key = 'planning'");
        assertThat(health.health("planning").state()).isEqualTo(ConnectorHealth.State.NOT_CONNECTED);
        assertThat(health.health("planning").notConfiguredReason()).contains("no configuration");

        configureLikeTheAdminScreen(url("/push"), "", "", SECRET);
        assertThat(health.health("planning").notConfiguredReason()).as("the old shape is a usable configuration").isNull();
    }
}
