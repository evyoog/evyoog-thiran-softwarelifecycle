package com.vyoog.signals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.integration.IntegrationConnection;
import com.vyoog.integration.IntegrationService;
import com.vyoog.integration.WebhookSignatureVerifier;
import com.vyoog.platform.audit.AuditService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0465/0505/0507: the signals were always real (VYB-0460–0463) — there was
 * simply no path out of Vyoog to anywhere. This is that path: a real HTTP POST to
 * whatever URL the "planning" {@link IntegrationConnection} (seeded OUTBOUND, V007)
 * is configured with, signed the same way {@link com.vyoog.integration.WebhookController}
 * verifies an inbound one — {@link WebhookSignatureVerifier#sign} is symmetric, so
 * reusing it here rather than inventing a second scheme is deliberate, not
 * incidental. {@code java.net.http.HttpClient} (JDK-native since 11) — no new
 * dependency for one outbound POST.
 */
@Service
public class SignalsExportService {

    private final SignalsService signals;
    private final IntegrationService integrations;
    private final AuditService audit;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static final String CONNECTION_KEY = "planning";

    public SignalsExportService(SignalsService signals, IntegrationService integrations,
                                 AuditService audit, ObjectMapper json) {
        this.signals = signals;
        this.integrations = integrations;
        this.audit = audit;
        this.json = json;
    }

    public record PushResult(boolean success, int statusCode, String error) {}

    /**
     * VYB-0505: what actually happens — a real POST, to a URL an administrator
     * configured, of exactly the scope's eight signals, signed so the receiving side
     * can verify it really came from here. Not a fire-and-forget: the outcome is
     * recorded on the connection (failures accumulate toward "degraded", same as an
     * inbound integration) and audited either way.
     */
    @Transactional
    public PushResult pushToDeliveryTool(List<UUID> capabilityIds, UUID actor) {
        IntegrationConnection conn = integrations.get(CONNECTION_KEY);
        String pushUrl = readConfigField(conn.getConfig(), "pushUrl");
        if (pushUrl == null || pushUrl.isBlank()) {
            throw new IllegalStateException(
                "No push URL configured for \"" + CONNECTION_KEY + "\" — set one in Administration first.");
        }
        String secret = conn.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "No shared secret configured for \"" + CONNECTION_KEY + "\" — the receiver couldn't verify this push anyway.");
        }

        ScopeSignals scope = signals.compute(capabilityIds);
        String body;
        try {
            body = json.writeValueAsString(Map.of(
                "capabilityIds", capabilityIds.stream().map(UUID::toString).toList(),
                "computedAt", scope.computedAt().toString(),
                "signals", Map.of(
                    "requirementCount", scope.requirementCount(),
                    "acceptanceCriteriaCount", scope.acceptanceCriteriaCount(),
                    "dependencyDepth", scope.dependencyDepth(),
                    "crossApplicationReach", scope.crossApplicationReach(),
                    "ambiguityLoad", scope.ambiguityLoad(),
                    "openGaps", scope.openGaps(),
                    "changeRate", scope.changeRate(),
                    "novelty", scope.novelty())));
        } catch (Exception e) {
            throw new IllegalStateException("Could not encode signals payload: " + e.getMessage(), e);
        }
        String signature = WebhookSignatureVerifier.sign(secret, body);

        PushResult result;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(pushUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Vyoog-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            result = new PushResult(ok, response.statusCode(), ok ? null : "HTTP " + response.statusCode() + ": " + response.body());
        } catch (IOException | InterruptedException e) {
            result = new PushResult(false, 0, e.getMessage());
        }

        // Persist through IntegrationService's own methods, not the locally-mutated
        // `conn` — both re-fetch and save for real, and setConnected(true, ...)
        // already resets failure/degraded state exactly the way a real success should.
        if (result.success()) {
            integrations.setConnected(CONNECTION_KEY, true, null);
        } else {
            integrations.recordFailure(CONNECTION_KEY, result.error());
        }
        audit.record(actor, "signals.pushed", "INTEGRATION_CONNECTION", null, null,
            Map.of("connection", CONNECTION_KEY, "success", result.success(), "statusCode", result.statusCode(),
                "capabilityIds", capabilityIds.stream().map(UUID::toString).toList()));
        return result;
    }

    private String readConfigField(String configJson, String field) {
        if (configJson == null) return null;
        try {
            var node = json.readTree(configJson);
            var value = node.get(field);
            return value == null ? null : value.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
