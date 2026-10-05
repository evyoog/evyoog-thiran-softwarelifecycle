package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.ScopeType;
import com.vyoog.integration.IntegrationConnection;
import com.vyoog.integration.IntegrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0917 (F40): the two read endpoints behind the Administration "Connector health" screen, over HTTP with
 * real tokens and a real database. They are platform-administrator only, and they never return a
 * connection's configuration, secret or payload (unlike {@code GET /api/v1/integrations}, which returns
 * {@code config}).
 */
@AutoConfigureMockMvc
class ConnectorHealthControllerIT extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired IntegrationService integrations;
    @Autowired ObjectMapper json;

    private JwtRequestPostProcessor signedInAs(String id) {
        return jwt().jwt(j -> j.subject("sub-" + id).claim("email", id + "@it.test").claim("preferred_username", id).claim("azp", "vyoog-web"));
    }

    private JwtRequestPostProcessor anAdministrator() {
        String id = unique("admin");
        java.util.UUID user = users.upsert("sub-" + id, id + "@it.test", id).getId();
        grants.grant(user, AccessRole.ADMINISTRATOR, ScopeType.PLATFORM, null, null, user);
        return signedInAs(id);
    }

    private JwtRequestPostProcessor anOrdinaryUser() {
        String id = unique("viewer");
        users.upsert("sub-" + id, id + "@it.test", id);
        return signedInAs(id);
    }

    private JsonNode body(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String newConnection() {
        String key = unique("hc");
        integrations.create(key, "health IT", IntegrationConnection.Direction.OUTBOUND);
        return key;
    }

    private void logRow(String key, String op, String idem, String status, int attempts, Integer http, String error, String startedAgo) {
        jdbc.update("""
            INSERT INTO connector_sync_log (connection_key, operation, idempotency_key, status, attempts, http_status, error,
                                            payload_bytes, payload_sha256, started_at, finished_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 10, 'x', clock_timestamp() - ?::interval, clock_timestamp() - ?::interval + interval '250 milliseconds')""",
            key, op, idem, status, attempts, http, error, startedAgo, startedAgo);
    }

    // ------------------------------------------------------------ who may read it

    @Test
    void VYB0917_AC1_noTokenIsRefusedWithoutAUser() throws Exception {
        mvc.perform(get("/api/v1/integrations/health")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/integrations/planning/sync-log")).andExpect(status().isUnauthorized());
    }

    @Test
    void VYB0917_AC1_aSignedInPersonWhoIsNotAnAdministratorGets403OnBothEndpoints() throws Exception {
        JwtRequestPostProcessor ordinary = anOrdinaryUser();
        mvc.perform(get("/api/v1/integrations/health").with(ordinary)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/integrations/planning/sync-log").with(ordinary)).andExpect(status().isForbidden());
    }

    @Test
    void VYB0917_AC1_aServiceAccountTokenIsRefused() throws Exception {
        // a token whose client is not the web client and carries no person: refused before any lookup
        mvc.perform(get("/api/v1/integrations/health").with(jwt().jwt(j -> j.subject("svc").claim("azp", "ci-bot"))))
            .andExpect(status().is4xxClientError());
    }

    // ------------------------------------------------------------ what it shows

    @Test
    void VYB0917_AC2_everyRegisteredConnectionIsListedWithItsStateAndWhatItIsFor() throws Exception {
        String key = newConnection();

        JsonNode all = body(mvc.perform(get("/api/v1/integrations/health").with(anAdministrator()))
            .andExpect(status().isOk()).andReturn());

        java.util.List<String> keys = new java.util.ArrayList<>();
        all.forEach(n -> keys.add(n.get("key").asText()));
        assertThat(keys).contains("planning", "git", "ci", "hr", key).isSorted();
        JsonNode mine = find(all, key);
        assertThat(mine.get("state").asText()).isEqualTo("NOT_CONNECTED");
        assertThat(mine.get("notConfiguredReason").asText()).contains("no configuration");
        assertThat(mine.get("owns").asText()).isEqualTo("health IT");
        assertThat(mine.get("direction").asText()).isEqualTo("OUTBOUND");
        assertThat(mine.get("lastSync").isNull()).isTrue();
        assertThat(mine.get("lastSuccessAt").isNull()).isTrue();
    }

    @Test
    void VYB0917_AC2_thePlanningConnectionShowsItsConnectorAndItsOperations() throws Exception {
        JsonNode planning = find(body(mvc.perform(get("/api/v1/integrations/health").with(anAdministrator())).andReturn()), "planning");

        assertThat(planning.get("connectorDescription").asText()).contains("Planning");
        java.util.List<String> operations = new java.util.ArrayList<>();
        planning.get("operations").forEach(n -> operations.add(n.asText()));
        assertThat(operations).containsExactly("brief.push", "signals.push");
    }

    @Test
    void VYB0917_AC2_aConnectionWithNoConnectorCodeSaysSoRatherThanShowingNothing() throws Exception {
        String key = newConnection();
        JsonNode mine = find(body(mvc.perform(get("/api/v1/integrations/health").with(anAdministrator())).andReturn()), key);
        assertThat(mine.get("connectorDescription").isNull()).isTrue();
        assertThat(mine.get("operations")).isEmpty();
    }

    @Test
    void VYB0917_AC3_aDegradedConnectionShowsItsFailuresItsLastErrorAndWhen() throws Exception {
        String key = newConnection();
        integrations.setConfig(key, "{\"baseUrl\":\"https://x.example.com\",\"auth\":[\"NONE\"]}");
        jdbc.update("UPDATE integration_connection SET connected = true, failure_count = 3, degraded = true, "
            + "last_error = 'HTTP 503: down', last_error_at = clock_timestamp() WHERE key = ?", key);

        JsonNode mine = find(body(mvc.perform(get("/api/v1/integrations/health").with(anAdministrator())).andReturn()), key);

        assertThat(mine.get("state").asText()).isEqualTo("DEGRADED");
        assertThat(mine.get("consecutiveFailures").asInt()).isEqualTo(3);
        assertThat(mine.get("lastError").asText()).isEqualTo("HTTP 503: down");
        assertThat(mine.get("lastErrorAt").asText()).isNotBlank();
        assertThat(mine.get("notConfiguredReason").isNull()).isTrue();
    }

    @Test
    void VYB0917_AC3_aHealthyConnectionShowsItsLastSuccessAndItsLatestSync() throws Exception {
        String key = newConnection();
        integrations.setConfig(key, "{\"baseUrl\":\"https://x.example.com\",\"auth\":[\"NONE\"]}");
        jdbc.update("UPDATE integration_connection SET connected = true WHERE key = ?", key);
        logRow(key, "function.upsert", "k-old", "FAILED", 3, 503, "HTTP 503: down", "10 minutes");
        logRow(key, "function.upsert", "k-new", "SUCCEEDED", 1, 200, null, "1 minute");

        JsonNode mine = find(body(mvc.perform(get("/api/v1/integrations/health").with(anAdministrator())).andReturn()), key);

        assertThat(mine.get("state").asText()).isEqualTo("HEALTHY");
        assertThat(mine.get("lastSuccessAt").asText()).isNotBlank();
        assertThat(mine.get("lastSync").get("idempotencyKey").asText()).isEqualTo("k-new");
        assertThat(mine.get("lastSync").get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(mine.get("lastSync").get("durationMs").asLong()).isBetween(200L, 2000L);
    }

    // ------------------------------------------------------------ inbound connections receive; they do not send

    private String newConnection(IntegrationConnection.Direction direction) {
        String key = unique("hi");
        integrations.create(key, "inbound IT", direction);
        return key;
    }

    @Test
    void VYB0917_AC3_anInboundConnectionIsNeverNotConfiguredAndIsHealthyOnceConnected() throws Exception {
        String key = newConnection(IntegrationConnection.Direction.INBOUND);
        JwtRequestPostProcessor admin = anAdministrator();

        JsonNode before = find(body(mvc.perform(get("/api/v1/integrations/health").with(admin)).andReturn()), key);
        assertThat(before.get("state").asText()).isEqualTo("NOT_CONNECTED");
        assertThat(before.get("notConfiguredReason").isNull()).as("it sends nothing, so it has no outbound setup to lack").isTrue();

        jdbc.update("UPDATE integration_connection SET connected = true WHERE key = ?", key);
        JsonNode after = find(body(mvc.perform(get("/api/v1/integrations/health").with(admin)).andReturn()), key);
        assertThat(after.get("state").asText()).isEqualTo("HEALTHY");
        assertThat(after.get("notConfiguredReason").isNull()).isTrue();
    }

    @Test
    void VYB0917_AC3_anInboundConnectionsLastSuccessIsItsLastVerifiedDelivery() throws Exception {
        String key = newConnection(IntegrationConnection.Direction.INBOUND);
        jdbc.update("UPDATE integration_connection SET connected = true WHERE key = ?", key);
        jdbc.update("INSERT INTO webhook_delivery (integration_key, delivery_id, received_at) VALUES (?, 'd1', clock_timestamp() - interval '2 hours')", key);
        jdbc.update("INSERT INTO webhook_delivery (integration_key, delivery_id, received_at) VALUES (?, 'd2', clock_timestamp() - interval '5 minutes')", key);

        JsonNode mine = find(body(mvc.perform(get("/api/v1/integrations/health").with(anAdministrator())).andReturn()), key);

        long ageSeconds = java.time.Duration.between(java.time.Instant.parse(mine.get("lastSuccessAt").asText()), java.time.Instant.now()).toSeconds();
        assertThat(ageSeconds).as("the newer delivery, about five minutes ago").isBetween(240L, 400L);
        assertThat(mine.get("lastSync").isNull()).as("nothing is sent from an inbound connection").isTrue();
    }

    @Test
    void VYB0917_AC3_aConnectionThatBothSendsAndReceivesUsesWhicheverSuccessIsNewer() throws Exception {
        String key = newConnection(IntegrationConnection.Direction.BOTH);
        integrations.setConfig(key, "{\"baseUrl\":\"https://x.example.com\",\"auth\":[\"NONE\"]}");
        jdbc.update("UPDATE integration_connection SET connected = true WHERE key = ?", key);
        logRow(key, "x.y", "k-1", "SUCCEEDED", 1, 200, null, "3 hours");
        jdbc.update("INSERT INTO webhook_delivery (integration_key, delivery_id, received_at) VALUES (?, 'd1', clock_timestamp() - interval '10 minutes')", key);

        JsonNode mine = find(body(mvc.perform(get("/api/v1/integrations/health").with(anAdministrator())).andReturn()), key);

        long ageSeconds = java.time.Duration.between(java.time.Instant.parse(mine.get("lastSuccessAt").asText()), java.time.Instant.now()).toSeconds();
        assertThat(ageSeconds).isBetween(540L, 700L);
    }

    // ------------------------------------------------------------ the sync log

    @Test
    void VYB0917_AC4_theSyncLogIsNewestFirstAndCarriesWhatHappenedToEachOperation() throws Exception {
        String key = newConnection();
        logRow(key, "function.upsert", "k-1", "FAILED", 3, 503, "HTTP 503: down", "30 minutes");
        logRow(key, "function.upsert", "k-2", "SUCCEEDED", 1, 200, null, "20 minutes");
        logRow(key, "function.upsert", "k-3", "FAILED", 1, 400, "HTTP 400: bad name", "10 minutes");

        JsonNode rows = body(mvc.perform(get("/api/v1/integrations/" + key + "/sync-log").with(anAdministrator()))
            .andExpect(status().isOk()).andReturn());

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).get("idempotencyKey").asText()).isEqualTo("k-3");
        assertThat(rows.get(2).get("idempotencyKey").asText()).isEqualTo("k-1");
        assertThat(rows.get(0).get("status").asText()).isEqualTo("FAILED");
        assertThat(rows.get(0).get("attempts").asInt()).isEqualTo(1);
        assertThat(rows.get(0).get("httpStatus").asInt()).isEqualTo(400);
        assertThat(rows.get(0).get("error").asText()).isEqualTo("HTTP 400: bad name");
        assertThat(rows.get(1).get("error").isNull()).isTrue();
    }

    @Test
    void VYB0917_AC4_theLimitIsHonouredAndKeptInsideItsBounds() throws Exception {
        String key = newConnection();
        for (int i = 1; i <= 5; i++) logRow(key, "x.y", "k-" + i, "SUCCEEDED", 1, 200, null, i + " minutes");
        JwtRequestPostProcessor admin = anAdministrator();

        assertThat(body(mvc.perform(get("/api/v1/integrations/" + key + "/sync-log?limit=2").with(admin)).andReturn())).hasSize(2);
        assertThat(body(mvc.perform(get("/api/v1/integrations/" + key + "/sync-log?limit=0").with(admin)).andReturn())).hasSize(1);
        assertThat(body(mvc.perform(get("/api/v1/integrations/" + key + "/sync-log?limit=-5").with(admin)).andReturn())).hasSize(1);
        assertThat(body(mvc.perform(get("/api/v1/integrations/" + key + "/sync-log?limit=100000").with(admin)).andReturn())).hasSize(5);
        assertThat(body(mvc.perform(get("/api/v1/integrations/" + key + "/sync-log").with(admin)).andReturn())).as("default 20").hasSize(5);
    }

    @Test
    void VYB0917_AC4_aConnectionThatSentNothingHasAnEmptyLogAndAnUnknownOneIs404() throws Exception {
        JwtRequestPostProcessor admin = anAdministrator();
        assertThat(body(mvc.perform(get("/api/v1/integrations/" + newConnection() + "/sync-log").with(admin))
            .andExpect(status().isOk()).andReturn())).isEmpty();
        mvc.perform(get("/api/v1/integrations/" + unique("nope") + "/sync-log").with(admin)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------ what it must never show

    @Test
    void VYB0917_AC5_noConfigurationSecretOrPayloadIsEverInTheResponse() throws Exception {
        String key = newConnection();
        integrations.setConfig(key, "{\"baseUrl\":\"https://x.example.com\",\"auth\":[\"API_KEY\",\"BEARER\",\"HMAC_SIGNATURE\"],"
            + "\"apiKey\":\"LEAK-API-KEY\",\"bearerToken\":\"LEAK-BEARER\"}");
        integrations.setConnected(key, true, "LEAK-HMAC-SECRET");
        logRow(key, "x.y", "k-1", "SUCCEEDED", 1, 200, null, "1 minute");
        JwtRequestPostProcessor admin = anAdministrator();

        String health = mvc.perform(get("/api/v1/integrations/health").with(admin)).andReturn().getResponse().getContentAsString();
        String log = mvc.perform(get("/api/v1/integrations/" + key + "/sync-log").with(admin)).andReturn().getResponse().getContentAsString();

        // (other connections' reasons may say "no baseUrl is set"; that names a missing setting, it is not a value)
        assertThat(health + log).doesNotContain("LEAK-API-KEY", "LEAK-BEARER", "LEAK-HMAC-SECRET", "x.example.com");
        assertThat(health).doesNotContain("\"config\"", "webhookSecret", "payloadSha256");
    }

    private static JsonNode find(JsonNode all, String key) {
        for (JsonNode n : all) if (n.get("key").asText().equals(key)) return n;
        throw new AssertionError("no connection " + key + " in " + all);
    }
}
