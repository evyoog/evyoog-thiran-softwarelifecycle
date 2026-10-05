package com.vyoog.integration.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.integration.IntegrationConnection;
import com.vyoog.integration.connector.ConnectorAuth;
import com.vyoog.integration.connector.ConnectorConfig;
import com.vyoog.integration.connector.ConnectorOperation;
import com.vyoog.integration.connector.ConnectorResult;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0916 (F16, F40): the "planning" connection reads the configuration the Administration screen has always
 * written, so replacing the hand-written pushes needs no one to re-enter a URL or a key.
 */
class PlanningConnectorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final PlanningConnector planning = new PlanningConnector();

    private static IntegrationConnection connection(String config, String secret) {
        IntegrationConnection c = new IntegrationConnection("planning", "delivery-tool push", IntegrationConnection.Direction.OUTBOUND);
        c.setConfig(config);
        c.setWebhookSecret(secret);
        return c;
    }

    // ------------------------------------------------------------ the old configuration shape keeps working

    @Test
    void VYB0916_AC2_theConfigurationTheAdministrationScreenWritesIsUsableAsItIs() {
        IntegrationConnection conn = connection("{\"pushUrl\":\"https://planning.example.com/hook\",\"apiKey\":\"k-1\",\"customerName\":\"Acme\"}", "s");

        assertThat(ConnectorConfig.problem(conn, JSON, planning)).isEmpty();
        ConnectorConfig config = ConnectorConfig.load(conn, JSON, planning);
        assertThat(config.baseUri().toString()).isEqualTo("https://planning.example.com/hook");
        assertThat(config.auth()).containsExactlyInAnyOrder(ConnectorAuth.HMAC_SIGNATURE, ConnectorAuth.API_KEY);
    }

    @Test
    void VYB0916_AC2_theBlankFieldsTheScreenSavesAreTreatedAsNotSet() {
        // the screen writes {"pushUrl", "apiKey", "customerName"} every time, with "" for what was left empty
        IntegrationConnection conn = connection("{\"pushUrl\":\"https://planning.example.com/hook\",\"apiKey\":\"\",\"customerName\":\"\"}", "s");

        ConnectorConfig config = ConnectorConfig.load(conn, JSON, planning);

        assertThat(config.auth()).as("no API key, so only the signature").containsExactly(ConnectorAuth.HMAC_SIGNATURE);
    }

    @Test
    void VYB0916_AC2_aConnectionAlreadyInTheNewShapeIsUsedAsItIsAndNeedsNoSecretWhenItDoesNotSign() {
        IntegrationConnection conn = connection(
            "{\"baseUrl\":\"https://planning.example.com/api\",\"auth\":[\"API_KEY\"],\"apiKey\":\"k-1\",\"pushUrl\":\"https://old.example.com/x\"}", null);

        ConnectorConfig config = ConnectorConfig.load(conn, JSON, planning);

        assertThat(config.baseUri().toString()).as("what is stored wins over the compatibility default").isEqualTo("https://planning.example.com/api");
        assertThat(config.auth()).containsExactly(ConnectorAuth.API_KEY);
    }

    @Test
    void VYB0916_AC2_withoutTheConnectorTheOldShapeIsNotUsableWhichIsWhyTheConnectorSuppliesTheDefaults() {
        IntegrationConnection conn = connection("{\"pushUrl\":\"https://planning.example.com/hook\"}", "s");
        assertThat(ConnectorConfig.problem(conn, JSON)).isPresent();
        assertThat(ConnectorConfig.problem(conn, JSON, planning)).isEmpty();
    }

    // ------------------------------------------------------------ the refusals an administrator has always seen

    @Test
    void VYB0916_AC3_noUrlConfiguredRefusesWithTheOldWords() {
        for (String config : new String[] {null, "", "{}", "{\"pushUrl\":\"\",\"apiKey\":\"k\"}", "not json"}) {
            assertThatThrownBy(() -> planning.requireConfigured(connection(config, "s"), JSON))
                .as("config=" + config).isInstanceOf(IllegalStateException.class)
                .hasMessage("No push URL configured for \"planning\" — set one in Administration first.");
        }
    }

    @Test
    void VYB0916_AC3_noSecretRefusesWithTheOldWords() {
        for (String secret : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> planning.requireConfigured(connection("{\"pushUrl\":\"https://planning.example.com/hook\"}", secret), JSON))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No shared secret configured for \"planning\" — the receiver couldn't verify this push anyway.");
        }
    }

    @Test
    void VYB0916_AC3_aSettingTheFrameworkRefusesIsNamedNotSilentlyIgnored() {
        assertThatThrownBy(() -> planning.requireConfigured(connection("{\"pushUrl\":\"http://planning.example.com/hook\"}", "s"), JSON))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("planning").hasMessageContaining("must be https");
        assertThatThrownBy(() -> planning.requireConfigured(connection("{\"pushUrl\":\"https://planning.example.com/hook?token=abc\"}", "s"), JSON))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("query");
        planning.requireConfigured(connection("{\"pushUrl\":\"http://localhost:8099/hook\"}", "s"), JSON); // localhost is fine
    }

    // ------------------------------------------------------------ one push as one operation

    @Test
    void VYB0916_AC1_theUrlIsSentExactlyAsConfiguredIncludingATrailingSlash() {
        ConnectorOperation plain = planning.operation(connection("{\"pushUrl\":\"https://p.example.com/hook\"}", "s"), JSON,
            "brief.push", "brief:1", "application/json", new byte[0]);
        ConnectorOperation slash = planning.operation(connection("{\"pushUrl\":\"https://p.example.com/hook/\"}", "s"), JSON,
            "brief.push", "brief:1", "application/json", new byte[0]);

        assertThat(plain.path()).isEmpty();
        assertThat(slash.path()).isEqualTo("/");
    }

    @Test
    void VYB0916_AC1_everyPushGetsItsOwnIdempotencyKeyWithTheGivenPrefix() {
        IntegrationConnection conn = connection("{\"pushUrl\":\"https://p.example.com/hook\"}", "s");
        ConnectorOperation a = planning.operation(conn, JSON, "signals.push", "signals", "application/json", new byte[0]);
        ConnectorOperation b = planning.operation(conn, JSON, "signals.push", "signals", "application/json", new byte[0]);

        assertThat(a.idempotencyKey()).startsWith("signals:").isNotEqualTo(b.idempotencyKey());
        assertThat(a.connectionKey()).isEqualTo("planning");
        assertThat(a.method()).isEqualTo("POST");
    }

    @Test
    void VYB0916_AC1_theConnectorDeclaresItsConnectionAndBothOperations() {
        assertThat(planning.connectionKey()).isEqualTo("planning");
        assertThat(planning.operations()).containsExactlyInAnyOrder("brief.push", "signals.push");
        assertThat(planning.description()).isNotBlank();
    }

    @Test
    void VYB0916_AC1_theResultIsReportedInTheShapeTheEndpointsHaveAlwaysReturned() {
        UUID log = UUID.randomUUID();
        ConnectorResult ok = new ConnectorResult(ConnectorResult.Outcome.SUCCEEDED, 1, 200, null, log);
        ConnectorResult refused = new ConnectorResult(ConnectorResult.Outcome.FAILED, 1, 400, "HTTP 400: no", log);
        ConnectorResult unreachable = new ConnectorResult(ConnectorResult.Outcome.FAILED, 3, null, "could not reach the receiver: ConnectException", log);
        ConnectorResult busy = new ConnectorResult(ConnectorResult.Outcome.IN_PROGRESS_ELSEWHERE, 0, null, null, log);

        assertThat(PlanningConnector.statusCode(ok)).isEqualTo(200);
        assertThat(PlanningConnector.error(ok)).isNull();
        assertThat(PlanningConnector.statusCode(refused)).isEqualTo(400);
        assertThat(PlanningConnector.error(refused)).isEqualTo("HTTP 400: no");
        assertThat(PlanningConnector.statusCode(unreachable)).isZero();
        assertThat(PlanningConnector.error(busy)).isEqualTo("not sent: in progress elsewhere");
    }

    // ------------------------------------------------------------ no database transaction across the call

    @Test
    void VYB0916_AC4_noPushHoldsADatabaseTransactionOpenAcrossTheSend() throws Exception {
        for (Class<?> type : new Class<?>[] {com.vyoog.brief.BriefPushService.class, com.vyoog.signals.SignalsExportService.class}) {
            assertThat(type.isAnnotationPresent(Transactional.class)).as(type.getSimpleName() + " class").isFalse();
            for (Method method : type.getDeclaredMethods()) {
                assertThat(method.isAnnotationPresent(Transactional.class)).as(type.getSimpleName() + "." + method.getName()).isFalse();
            }
        }
    }
}
