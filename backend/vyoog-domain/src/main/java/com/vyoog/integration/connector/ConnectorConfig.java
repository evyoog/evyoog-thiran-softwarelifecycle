package com.vyoog.integration.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.integration.IntegrationConnection;
import java.net.URI;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * VYB-0913: what the engine needs to know about one connection, read from its registry row.
 *
 * <pre>
 * integration_connection.config = {
 *   "baseUrl": "https://planner.example.com/api",      required; https, or http only for localhost
 *   "auth": ["HMAC_SIGNATURE", "API_KEY"],               required; "NONE" to say there is none
 *   "apiKey": "...", "apiKeyHeader": "X-API-Key",        for API_KEY (header optional)
 *   "bearerToken": "..."                                 for BEARER
 * }
 * integration_connection.webhook_secret                  the HMAC secret, for HMAC_SIGNATURE
 * </pre>
 *
 * The secrets live where the registry already keeps them (the {@code config} JSON and
 * {@code webhook_secret}); this class only reads them. They are never logged, never put in an error
 * message and never in {@link #toString()}.
 *
 * <p>The base URL is checked once, here: https only (a key or signature over cleartext is a leak),
 * except for a loopback host so it can be tested and run locally; no credentials in the URL, no
 * query or fragment. Redirects are not followed (see {@link ConnectorExecutor}), so a receiver
 * cannot bounce the request, and the headers with it, to another host.
 */
public final class ConnectorConfig {

    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,60}");
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");

    private final String connectionKey;
    private final URI baseUri;
    private final Set<ConnectorAuth> auth;
    private final String apiKeyHeader;
    private final String apiKey;
    private final String bearerToken;
    private final String hmacSecret;

    private ConnectorConfig(String connectionKey, URI baseUri, Set<ConnectorAuth> auth, String apiKeyHeader,
                            String apiKey, String bearerToken, String hmacSecret) {
        this.connectionKey = connectionKey;
        this.baseUri = baseUri;
        this.auth = auth;
        this.apiKeyHeader = apiKeyHeader;
        this.apiKey = apiKey;
        this.bearerToken = bearerToken;
        this.hmacSecret = hmacSecret;
    }

    /** @throws ConnectorNotConfiguredException naming what is missing or wrong */
    public static ConnectorConfig load(IntegrationConnection connection, ObjectMapper json) {
        String key = connection.getKey();
        Optional<String> problem = problem(connection, json);
        if (problem.isPresent()) throw new ConnectorNotConfiguredException(key, problem.get());
        JsonNode node = parse(connection.getConfig(), json);
        return new ConnectorConfig(key, URI.create(text(node, "baseUrl").replaceAll("/+$", "")), authOf(node),
            text(node, "apiKeyHeader") == null ? "X-API-Key" : text(node, "apiKeyHeader"),
            text(node, "apiKey"), text(node, "bearerToken"), connection.getWebhookSecret());
    }

    /** Why this connection cannot be used, or empty if it can. Used by the health state too. */
    public static Optional<String> problem(IntegrationConnection connection, ObjectMapper json) {
        JsonNode node = parse(connection.getConfig(), json);
        if (node == null) return Optional.of("no configuration is set");

        String baseUrl = text(node, "baseUrl");
        if (baseUrl == null) return Optional.of("no baseUrl is set");
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (java.net.URISyntaxException e) {
            return Optional.of("baseUrl is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (host.isEmpty()) return Optional.of("baseUrl has no host");
        if (uri.getUserInfo() != null) return Optional.of("baseUrl must not contain credentials");
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) return Optional.of("baseUrl must not have a query or fragment");
        if (!scheme.equals("https") && !(scheme.equals("http") && LOOPBACK.contains(host))) {
            return Optional.of("baseUrl must be https (http is allowed only for localhost)");
        }

        Set<ConnectorAuth> auth = authOf(node);
        if (auth == null) return Optional.of("auth is not set (list the schemes, or [\"NONE\"] for none)");
        if (auth.isEmpty()) return Optional.of("auth lists no known scheme");
        if (auth.contains(ConnectorAuth.NONE) && auth.size() > 1) return Optional.of("auth NONE cannot be combined with another scheme");
        if (auth.contains(ConnectorAuth.API_KEY)) {
            if (blank(text(node, "apiKey"))) return Optional.of("auth API_KEY needs apiKey");
            String header = text(node, "apiKeyHeader");
            if (header != null && !HEADER_NAME.matcher(header).matches()) return Optional.of("apiKeyHeader is not a valid header name");
        }
        if (auth.contains(ConnectorAuth.BEARER) && blank(text(node, "bearerToken"))) return Optional.of("auth BEARER needs bearerToken");
        if (auth.contains(ConnectorAuth.HMAC_SIGNATURE) && blank(connection.getWebhookSecret())) {
            return Optional.of("auth HMAC_SIGNATURE needs the connection's shared secret");
        }
        return Optional.empty();
    }

    private static JsonNode parse(String config, ObjectMapper json) {
        if (config == null || config.isBlank()) return null;
        try {
            JsonNode node = json.readTree(config);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** null if {@code auth} is absent; empty if present but no entry is a known scheme. */
    private static Set<ConnectorAuth> authOf(JsonNode node) {
        JsonNode list = node.get("auth");
        if (list == null || !list.isArray()) return null;
        Set<ConnectorAuth> out = EnumSet.noneOf(ConnectorAuth.class);
        for (JsonNode item : list) {
            try {
                out.add(ConnectorAuth.valueOf(item.asText().trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // an unknown name is left out; if nothing is left, problem() says so
            }
        }
        return out;
    }

    /** {@code text} with every secret of this connection that appears in it replaced, so a receiver that echoes one back cannot get it into a log. */
    public String redact(String text) {
        String out = text;
        for (String secret : new String[] {apiKey, bearerToken, hmacSecret}) {
            if (secret != null && !secret.isBlank()) out = out.replace(secret, "[redacted]");
        }
        return out;
    }

    public String connectionKey() { return connectionKey; }
    public URI baseUri() { return baseUri; }
    public Set<ConnectorAuth> auth() { return auth; }
    String apiKeyHeader() { return apiKeyHeader; }
    String apiKey() { return apiKey; }
    String bearerToken() { return bearerToken; }
    String hmacSecret() { return hmacSecret; }

    @Override
    public String toString() {
        return "ConnectorConfig[" + connectionKey + " " + baseUri + " auth=" + auth + "]";
    }
}
