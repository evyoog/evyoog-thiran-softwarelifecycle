package com.vyoog.signals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.integration.IntegrationConnection;
import com.vyoog.integration.IntegrationService;
import com.vyoog.integration.connector.ConnectorExecutor;
import com.vyoog.integration.connector.ConnectorResult;
import com.vyoog.integration.planning.PlanningConnector;
import com.vyoog.platform.audit.AuditService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

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
    private final PlanningConnector planning;
    private final ConnectorExecutor connectors;
    private final AuditService audit;
    private final ObjectMapper json;

    public SignalsExportService(SignalsService signals, IntegrationService integrations, PlanningConnector planning,
                                 ConnectorExecutor connectors, AuditService audit, ObjectMapper json) {
        this.signals = signals;
        this.integrations = integrations;
        this.planning = planning;
        this.connectors = connectors;
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
     *
     * <p>VYB-0916: the request goes out through the connector framework (see {@link PlanningConnector}),
     * which signs it, retries it, logs it and updates the connection's health. This method is no longer
     * transactional, so no database transaction is held open across the call.
     */
    public PushResult pushToDeliveryTool(List<UUID> capabilityIds, UUID actor) {
        IntegrationConnection conn = integrations.get(PlanningConnector.KEY);
        planning.requireConfigured(conn, json);

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

        ConnectorResult sent = connectors.execute(planning.operation(conn, json, PlanningConnector.SIGNALS_PUSH,
            "signals", "application/json", body.getBytes(StandardCharsets.UTF_8)));
        PushResult result = new PushResult(sent.succeeded(), PlanningConnector.statusCode(sent), PlanningConnector.error(sent));

        audit.record(actor, "signals.pushed", "INTEGRATION_CONNECTION", null, null,
            Map.of("connection", PlanningConnector.KEY, "success", result.success(), "statusCode", result.statusCode(),
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
