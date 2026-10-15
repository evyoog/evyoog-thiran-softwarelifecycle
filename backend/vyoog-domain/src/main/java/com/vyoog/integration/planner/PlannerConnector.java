package com.vyoog.integration.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.vyoog.integration.connector.Connector;
import com.vyoog.integration.connector.ConnectorOperation;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * VYB-0918a (D24, D52): the Agile Planner as a {@link Connector}: the signed webhooks this application sends it, as the Planner's own
 * documents define them ({@code POST /webhooks/alm}, the header {@code X-ALM-Signature: hmac-sha256=<hex>}). The connection is the
 * registry row {@code agile-planner}; an administrator sets its {@code baseUrl} and shared secret and the rest is filled in here.
 * What the Planner sends this application is received by {@code PlannerRequirementIntake}, not here.
 */
@Component
public class PlannerConnector implements Connector {

    public static final String KEY = "agile-planner";
    public static final String WEBHOOK = "planner.webhook";
    static final String PATH = "/webhooks/alm";

    @Override
    public String connectionKey() {
        return KEY;
    }

    @Override
    public String description() {
        return "Agile Planner: signed webhooks about the requirements it sent (status changes, and in later rows designs, test cases and results).";
    }

    @Override
    public Set<String> operations() {
        return Set.of(WEBHOOK);
    }

    /** The Planner's signature scheme, used wherever the stored configuration does not say otherwise: only {@code baseUrl} and the secret are needed. */
    @Override
    public Map<String, Object> compatibilityDefaults(JsonNode stored) {
        return Map.of("auth", java.util.List.of("HMAC_SIGNATURE"), "signatureHeader", "X-ALM-Signature", "signaturePrefix", "hmac-sha256=");
    }

    /** One event as an operation. The key is the event's id, so a retry carries the same one and an event that succeeded is never sent twice. */
    public ConnectorOperation operation(UUID eventId, String payloadJson) {
        return ConnectorOperation.postJson(KEY, WEBHOOK, "planner-event:" + eventId, PATH, payloadJson);
    }
}
