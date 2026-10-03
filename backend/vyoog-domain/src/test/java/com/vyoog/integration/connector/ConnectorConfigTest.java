package com.vyoog.integration.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.integration.IntegrationConnection;
import org.junit.jupiter.api.Test;

/** VYB-0913 (F40): what makes a connection usable, and that no secret leaks out of the configuration. */
class ConnectorConfigTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static IntegrationConnection connection(String config, String secret) {
        IntegrationConnection c = new IntegrationConnection("planning", "delivery-tool push", IntegrationConnection.Direction.OUTBOUND);
        c.setConfig(config);
        c.setWebhookSecret(secret);
        return c;
    }

    private static String problem(String config, String secret) {
        return ConnectorConfig.problem(connection(config, secret), JSON).orElse(null);
    }

    @Test
    void VYB0913_AC3_aCompleteConfigurationIsUsable() {
        String config = """
            {"baseUrl":"https://planner.example.com/api/","auth":["HMAC_SIGNATURE","API_KEY"],"apiKey":"k-123"}""";
        assertThat(problem(config, "shared-secret")).isNull();
        ConnectorConfig loaded = ConnectorConfig.load(connection(config, "shared-secret"), JSON);
        assertThat(loaded.baseUri().toString()).isEqualTo("https://planner.example.com/api");
        assertThat(loaded.auth()).containsExactlyInAnyOrder(ConnectorAuth.HMAC_SIGNATURE, ConnectorAuth.API_KEY);
    }

    @Test
    void VYB0913_AC3_missingPiecesAreNamedAndNothingIsSent() {
        assertThat(problem(null, null)).contains("no configuration");
        assertThat(problem("{}", null)).contains("no baseUrl");
        assertThat(problem("{\"baseUrl\":\"https://x.example.com\"}", null)).contains("auth is not set");
        assertThat(problem("{\"baseUrl\":\"https://x.example.com\",\"auth\":[\"API_KEY\"]}", null)).contains("needs apiKey");
        assertThat(problem("{\"baseUrl\":\"https://x.example.com\",\"auth\":[\"BEARER\"]}", null)).contains("needs bearerToken");
        assertThat(problem("{\"baseUrl\":\"https://x.example.com\",\"auth\":[\"HMAC_SIGNATURE\"]}", null)).contains("shared secret");
        assertThat(problem("{\"baseUrl\":\"https://x.example.com\",\"auth\":[\"NONE\",\"API_KEY\"],\"apiKey\":\"k\"}", null)).contains("NONE cannot be combined");
        assertThat(problem("{\"baseUrl\":\"https://x.example.com\",\"auth\":[\"MAGIC\"]}", null)).contains("no known scheme");
        assertThat(problem("not json", null)).contains("no configuration");
    }

    @Test
    void VYB0913_AC3_deliberatelyNoAuthMustBeWrittenDown() {
        assertThat(problem("{\"baseUrl\":\"https://x.example.com\",\"auth\":[\"NONE\"]}", null)).isNull();
        assertThat(problem("{\"baseUrl\":\"https://x.example.com\"}", null)).isNotNull();
    }

    @Test
    void VYB0913_AC4_credentialsNeverTravelOverPlainHttpToARemoteHost() {
        String none = "\"auth\":[\"NONE\"]";
        assertThat(problem("{\"baseUrl\":\"http://planner.example.com\"," + none + "}", null)).contains("must be https");
        assertThat(problem("{\"baseUrl\":\"ftp://planner.example.com\"," + none + "}", null)).contains("must be https");
        assertThat(problem("{\"baseUrl\":\"http://localhost:8099\"," + none + "}", null)).isNull();
        assertThat(problem("{\"baseUrl\":\"http://127.0.0.1:8099\"," + none + "}", null)).isNull();
        assertThat(problem("{\"baseUrl\":\"http://localhost.evil.example.com\"," + none + "}", null)).contains("must be https");
    }

    @Test
    void VYB0913_AC4_aBaseUrlCannotCarryCredentialsOrAQuery() {
        String none = "\"auth\":[\"NONE\"]";
        assertThat(problem("{\"baseUrl\":\"https://user:pw@planner.example.com\"," + none + "}", null)).contains("credentials");
        assertThat(problem("{\"baseUrl\":\"https://planner.example.com?x=1\"," + none + "}", null)).contains("query");
        assertThat(problem("{\"baseUrl\":\"https://planner.example.com#f\"," + none + "}", null)).contains("query or fragment");
    }

    @Test
    void VYB0913_AC4_theApiKeyHeaderNameIsChecked() {
        String config = "{\"baseUrl\":\"https://x.example.com\",\"auth\":[\"API_KEY\"],\"apiKey\":\"k\",\"apiKeyHeader\":\"X-Key\\r\\nX-Evil\"}";
        assertThat(problem(config, null)).contains("apiKeyHeader");
    }

    @Test
    void VYB0913_AC4_noSecretAppearsInToStringOrInTheNotConfiguredMessage() {
        String config = "{\"baseUrl\":\"https://x.example.com\",\"auth\":[\"API_KEY\",\"BEARER\",\"HMAC_SIGNATURE\"],"
            + "\"apiKey\":\"APIKEY-SECRET\",\"bearerToken\":\"BEARER-SECRET\"}";
        ConnectorConfig loaded = ConnectorConfig.load(connection(config, "HMAC-SECRET"), JSON);
        assertThat(loaded.toString()).doesNotContain("APIKEY-SECRET", "BEARER-SECRET", "HMAC-SECRET");

        assertThatThrownBy(() -> ConnectorConfig.load(connection("{\"baseUrl\":\"http://x.example.com\",\"apiKey\":\"APIKEY-SECRET\"}", "HMAC-SECRET"), JSON))
            .isInstanceOf(ConnectorNotConfiguredException.class)
            .hasMessageNotContaining("APIKEY-SECRET").hasMessageNotContaining("HMAC-SECRET")
            .hasMessageContaining("planning");
    }

    @Test
    void VYB0913_AC4_aSecretTheReceiverEchoesBackIsRemovedBeforeItCanBeLogged() {
        String config = "{\"baseUrl\":\"https://x.example.com\",\"auth\":[\"API_KEY\",\"HMAC_SIGNATURE\"],\"apiKey\":\"APIKEY-SECRET\"}";
        ConnectorConfig loaded = ConnectorConfig.load(connection(config, "HMAC-SECRET"), JSON);
        assertThat(loaded.redact("bad key APIKEY-SECRET and secret HMAC-SECRET")).isEqualTo("bad key [redacted] and secret [redacted]");
        assertThat(loaded.redact("nothing here")).isEqualTo("nothing here");
    }
}
