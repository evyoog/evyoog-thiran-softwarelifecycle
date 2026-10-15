package com.vyoog.integration.planner;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.integration.IntegrationConnection;
import com.vyoog.integration.connector.ConnectorConfig;
import com.vyoog.integration.connector.ConnectorOperation;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** VYB-0918a: the rules of telling the Agile Planner back that need no database. */
class PlannerWebhookRulesTest {

    @Test
    void VYB0918_AC2_theStatusPayloadCarriesTheContractsFieldsWithLowerCaseStatuses() throws Exception {
        UUID eventId = UUID.randomUUID();
        var payload = PlannerOutboundEvents.statusChangedPayload(eventId, Instant.parse("2026-10-15T09:00:00Z"), "VY-42", "FN-123",
            "IN_REVIEW", "NEEDS_REVISION", 7);
        JsonNode json = new ObjectMapper().valueToTree(payload);
        assertThat(json.get("event").asText()).isEqualTo("requirement.status_changed");
        assertThat(json.get("event_id").asText()).isEqualTo(eventId.toString());
        assertThat(json.get("occurred_at").asText()).isEqualTo("2026-10-15T09:00:00Z");
        assertThat(json.get("version").asLong()).isEqualTo(7);
        assertThat(json.get("requirement_id").asText()).isEqualTo("VY-42");
        assertThat(json.get("source_function_id").asText()).isEqualTo("FN-123");
        assertThat(json.get("status").asText()).isEqualTo("needs_revision");
        assertThat(json.get("previous_status").asText()).isEqualTo("in_review");
    }

    @Test
    void VYB0918_AC4_afterAFailedAttemptTheWaitIsOneTwoFourThenEightMinutes() {
        assertThat(PlannerWebhookDelivery.backoffAfter(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(PlannerWebhookDelivery.backoffAfter(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(PlannerWebhookDelivery.backoffAfter(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(PlannerWebhookDelivery.backoffAfter(4)).isEqualTo(Duration.ofMinutes(8));
        assertThat(PlannerWebhookDelivery.backoffAfter(9)).isEqualTo(Duration.ofMinutes(8));
    }

    @Test
    void VYB0918_AC1_theConnectorFillsInThePlannersSignatureSchemeSoOnlyTheUrlAndSecretAreNeeded() {
        IntegrationConnection c = new IntegrationConnection(PlannerConnector.KEY, "Planner", IntegrationConnection.Direction.BOTH);
        c.setConfig("{\"baseUrl\":\"https://planner.example.com\"}");
        c.setWebhookSecret("shared");
        PlannerConnector connector = new PlannerConnector();
        assertThat(ConnectorConfig.problem(c, new ObjectMapper(), connector)).isEmpty();
        ConnectorConfig loaded = ConnectorConfig.load(c, new ObjectMapper(), connector);
        assertThat(loaded.baseUri().toString()).isEqualTo("https://planner.example.com");
        // without the secret it is not usable, and says so
        c.setWebhookSecret(null);
        assertThat(ConnectorConfig.problem(c, new ObjectMapper(), connector)).hasValueSatisfying(p -> assertThat(p).contains("shared secret"));
        // and without a URL
        c.setConfig(null);
        assertThat(ConnectorConfig.problem(c, new ObjectMapper(), connector)).isPresent();
    }

    @Test
    void VYB0918_AC1_oneEventIsOneOperationKeyedByItsIdToTheWebhookPath() {
        UUID id = UUID.randomUUID();
        ConnectorOperation op = new PlannerConnector().operation(id, "{\"event\":\"x\"}");
        assertThat(op.connectionKey()).isEqualTo("agile-planner");
        assertThat(op.path()).isEqualTo("/webhooks/alm");
        assertThat(op.method()).isEqualTo("POST");
        assertThat(op.idempotencyKey()).isEqualTo("planner-event:" + id);
    }
}
