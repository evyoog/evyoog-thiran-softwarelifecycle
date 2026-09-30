package com.vyoog.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.vyoog.brief.BriefPushService;
import com.vyoog.brief.BriefSection;
import com.vyoog.brief.BriefService;
import com.vyoog.brief.BriefTarget;
import com.vyoog.evidence.TestCase;
import com.vyoog.evidence.TestCaseService;
import com.vyoog.integration.IntegrationConnection;
import com.vyoog.integration.IntegrationService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * VYB-0837: {@code brief_capability} persists the real generation-time capability
 * selection, and {@link BriefPushService} reads it back and sends the right
 * level/capabilityName/productName/appName plus the markdown as a real multipart file
 * part — all against real Postgres, with a real (local, ephemeral) HTTP server
 * standing in for the planning tool.
 *
 * <p>VYB-0903: this used to overwrite the seeded "planning" {@code integration_connection}
 * row (the one real deployments configure) and put it back afterwards, so a crash mid-run
 * left a fake push URL and secret in place. It now replaces {@link IntegrationService} with
 * an in-memory one holding its own "planning" connection, so the database row is never read
 * or written. Same explicit-name-only convention as every other {@code *VerificationRunner}
 * — invisible to {@code mvn test}/{@code mvn verify} — and, like them, it only ever runs
 * against a local database ({@link VerificationRunnerBase}).
 *
 * <pre>
 * mvn -pl vyoog-api -am test -Dtest=BriefPushVerificationRunner \
 *   -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
class BriefPushVerificationRunner extends VerificationRunnerBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired BriefService briefService;
    @Autowired BriefPushService briefPushService;
    @Autowired TestCaseService testCaseService;
    @Autowired InMemoryIntegrations integrations;

    /** VYB-0903: a stand-in for IntegrationService that owns its own "planning" connection and never touches the table. */
    static class InMemoryIntegrations extends IntegrationService {
        final IntegrationConnection planning = new IntegrationConnection(
            "planning", "delivery-tool push", IntegrationConnection.Direction.OUTBOUND);

        InMemoryIntegrations() {
            super(null, null, null);
        }

        @Override public IntegrationConnection get(String key) {
            if (!"planning".equals(key)) throw new java.util.NoSuchElementException(key);
            return planning;
        }

        @Override public IntegrationConnection setConnected(String key, boolean connected, String webhookSecret) {
            planning.setConnected(connected);
            if (webhookSecret != null) planning.setWebhookSecret(webhookSecret);
            return planning;
        }

        @Override public void recordFailure(String key, String error) {
            planning.recordFailure(error);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean @Primary InMemoryIntegrations inMemoryIntegrations() {
            return new InMemoryIntegrations();
        }
    }

    private String planningRow() {
        return jdbc.queryForObject(
            "SELECT connected || '|' || coalesce(config::text, '') || '|' || coalesce(webhook_secret, '') "
                + "|| '|' || failure_count FROM integration_connection WHERE key = 'planning'", String.class);
    }

    @Test
    void capabilityScopedBriefPersistsItsScopeAndPushesTheRightMetadataAndFileAgainstRealPostgres() throws IOException {
        // The table's "planning" row is never read or written (see the class comment).
        String rowBefore = planningRow();

        UUID productId = jdbc.queryForObject(
            "INSERT INTO product (key, name) VALUES ('VYB0837', 'VYB-0837 Product') RETURNING id", UUID.class);
        UUID appId = jdbc.queryForObject(
            "INSERT INTO application (product_id, name) VALUES (?, 'VYB-0837 App') RETURNING id", UUID.class, productId);
        UUID capId = jdbc.queryForObject(
            "INSERT INTO capability (application_id, name) VALUES (?, 'VYB-0837 Cap') RETURNING id", UUID.class, appId);
        UUID devId = jdbc.queryForObject("""
            INSERT INTO app_user (subject, email, display_name)
            VALUES ('vyb0837-dev-sub', 'dev@vyb0837.test', 'VYB-0837 Dev') RETURNING id
            """, UUID.class);
        UUID reqId = jdbc.queryForObject("""
            INSERT INTO requirement (key, capability_id, type, status, title, statement, revision)
            VALUES ('VY-0837', ?, 'FUNCTIONAL', 'APPROVED', 'Req', 'statement', 1) RETURNING id
            """, UUID.class, capId);
        var tc = testCaseService.draft("Test", "steps", TestCase.Category.INDIVIDUAL, reqId, devId);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedContentType = new AtomicReference<>();
        AtomicReference<String> capturedApiKey = new AtomicReference<>();
        server.createContext("/push", exchange -> {
            capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            capturedApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();

        try {
            var brief = briefService.generate(appId, "VYB-0837 App", List.of(capId), BriefTarget.HUMAN, devId, devId,
                BriefSection.ALL, false);

            var scopedCaps = jdbc.queryForList(
                "SELECT capability_id FROM brief_capability WHERE brief_id = ?", UUID.class, brief.getId());
            System.out.println("[verify] brief_capability rows -> " + scopedCaps);
            assertThat(scopedCaps).containsExactly(capId);

            integrations.planning.setConfig("{\"pushUrl\":\"http://localhost:" + server.getAddress().getPort() + "/push\","
                + "\"apiKey\":\"verify-api-key\",\"customerName\":\"VYB-0837 Customer\"}");
            integrations.planning.setWebhookSecret("verify-secret");

            var result = briefPushService.push(brief.getId(), devId);
            System.out.println("[verify] push result -> " + result);
            assertThat(result.success()).isTrue();
            assertThat(capturedContentType.get()).startsWith("multipart/form-data; boundary=");
            assertThat(capturedApiKey.get()).isEqualTo("verify-api-key");
            assertThat(capturedBody.get())
                .contains("name=\"projectName\"").contains("VYB-0837 Cap")
                .contains("name=\"customerName\"").contains("VYB-0837 Customer")
                .contains("name=\"productName\"").contains("VYB-0837 Product")
                .contains("name=\"appName\"").contains("VYB-0837 App")
                .contains("name=\"level\"").contains("CAPABILITY")
                .contains("name=\"capabilityName\"").contains("VYB-0837 Cap")
                .contains("name=\"file\"; filename=\"VY-vyb-0837-app-implementation-brief.md\"")
                .contains("Content-Type: text/markdown");
            assertThat(integrations.planning.isConnected()).isTrue();
            assertThat(planningRow()).as("the real integration_connection row is untouched").isEqualTo(rowBefore);
        } finally {
            server.stop(0);
            jdbc.update("DELETE FROM test_case WHERE id = ?", tc.getId());
            jdbc.update("DELETE FROM trace_link WHERE to_id = ? OR from_id = ?", reqId, tc.getId());
            jdbc.update("DELETE FROM brief_capability WHERE capability_id = ?", capId);
            jdbc.update("DELETE FROM brief_requirement WHERE requirement_id = ?", reqId);
            jdbc.update("DELETE FROM brief WHERE application_id = ?", appId);
            jdbc.update("DELETE FROM requirement WHERE id = ?", reqId);
            jdbc.update("DELETE FROM capability WHERE id = ?", capId);
            jdbc.update("DELETE FROM application WHERE id = ?", appId);
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
            jdbc.update("DELETE FROM app_user WHERE id = ?", devId);
        }
    }
}
