package com.vyoog.integration.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.integration.IntegrationConnection;
import com.vyoog.integration.connector.Connector;
import com.vyoog.integration.connector.ConnectorConfig;
import com.vyoog.integration.connector.ConnectorOperation;
import com.vyoog.integration.connector.ConnectorResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * VYB-0916 (F16, F40): the "planning" connection as a {@link Connector}. It replaces the two hand-written
 * HTTP pushes ({@code BriefPushService}, {@code SignalsExportService}), which each built their own request,
 * had no retries, no idempotency key, no sync log, and made the call inside a database transaction. They now
 * build the same payloads and hand them to the framework's {@code ConnectorExecutor}.
 *
 * <p><b>Compatibility.</b> The wire format is unchanged: a JSON or {@code multipart/form-data} body, signed
 * with HMAC-SHA256 over the exact body bytes in {@code X-Vyoog-Signature} (bare hex), plus {@code X-API-Key}
 * when an API key is configured. The Administration screen still writes the old configuration shape
 * ({@code {"pushUrl", "apiKey", "customerName"}}) and the secret in {@code webhook_secret}, so this connector
 * reads that shape: {@code pushUrl} stands in for {@code baseUrl}, and when {@code auth} is not set the
 * schemes are the ones the old push always used (the signature, and the API key if there is one). A
 * connection that already uses the new shape ({@code baseUrl}, {@code auth}) is used as it is. Nothing needs
 * editing for an existing connection to keep working.
 *
 * <p>What does change, because the framework enforces it: the URL must be https (plain http only for
 * localhost), cannot carry a query string, and a failing push is retried a few times before it is reported.
 */
@Component
public class PlanningConnector implements Connector {

    public static final String KEY = "planning";
    public static final String BRIEF_PUSH = "brief.push";
    public static final String SIGNALS_PUSH = "signals.push";

    @Override
    public String connectionKey() {
        return KEY;
    }

    @Override
    public String description() {
        return "Planning / delivery tool: implementation briefs and scope signals, pushed as signed payloads.";
    }

    @Override
    public Set<String> operations() {
        return Set.of(BRIEF_PUSH, SIGNALS_PUSH);
    }

    /** {@code pushUrl} as the base URL, and the signature (plus the API key, if set) as the auth, when the stored configuration says neither. */
    @Override
    public Map<String, Object> compatibilityDefaults(JsonNode stored) {
        Map<String, Object> defaults = new java.util.LinkedHashMap<>();
        String pushUrl = text(stored, "pushUrl");
        if (pushUrl != null) defaults.put("baseUrl", stripTrailingSlashes(pushUrl));
        List<String> auth = new ArrayList<>(List.of("HMAC_SIGNATURE"));
        if (text(stored, "apiKey") != null) auth.add("API_KEY");
        defaults.put("auth", auth);
        return defaults;
    }

    /**
     * Refuses, with a named reason, when nothing can be pushed yet. The two messages an administrator has
     * always seen for a missing URL or secret are kept word for word.
     *
     * @throws IllegalStateException which the API reports as 409 with this text
     */
    public void requireConfigured(IntegrationConnection connection, ObjectMapper json) {
        Optional<String> problem = ConnectorConfig.problem(connection, json, this);
        if (problem.isEmpty()) return;
        String reason = problem.get();
        if (reason.equals("no configuration is set") || reason.equals("no baseUrl is set")) {
            throw new IllegalStateException(
                "No push URL configured for \"" + KEY + "\" — set one in Administration first.");
        }
        if (reason.contains("shared secret")) {
            throw new IllegalStateException(
                "No shared secret configured for \"" + KEY + "\" — the receiver couldn't verify this push anyway.");
        }
        throw new IllegalStateException("Connection \"" + KEY + "\" is not configured: " + reason);
    }

    /**
     * One push as an operation. Every push has always sent (a second click on "push" sends again), so each
     * gets its own idempotency key; the retries inside one push share it, which is what lets a receiver
     * recognise a retried request.
     *
     * <p>The URL is sent exactly as configured: the base is {@code pushUrl} without trailing slashes, and a
     * trailing slash, if there was one, is the operation's path.
     */
    public ConnectorOperation operation(IntegrationConnection connection, ObjectMapper json, String operation,
                                        String keyPrefix, String contentType, byte[] body) {
        String configured = text(parse(connection.getConfig(), json), "pushUrl");
        String path = configured != null && configured.endsWith("/") ? "/" : "";
        return new ConnectorOperation(KEY, operation, keyPrefix + ":" + UUID.randomUUID(), "POST", path, contentType, body);
    }

    /** The HTTP status the receiver answered, or 0 when there was none (the shape the push endpoints have always returned). */
    public static int statusCode(ConnectorResult result) {
        return result.httpStatus() == null ? 0 : result.httpStatus();
    }

    /** Why a push failed, or null if it did not. */
    public static String error(ConnectorResult result) {
        if (result.succeeded()) return null;
        return result.error() != null ? result.error() : "not sent: " + result.outcome().name().toLowerCase().replace('_', ' ');
    }

    private static JsonNode parse(String config, ObjectMapper json) {
        if (config == null || config.isBlank()) return null;
        try {
            return json.readTree(config);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static String stripTrailingSlashes(String url) {
        return url.replaceAll("/+$", "");
    }
}
