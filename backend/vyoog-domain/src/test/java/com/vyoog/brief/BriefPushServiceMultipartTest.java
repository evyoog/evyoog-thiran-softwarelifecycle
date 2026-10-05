package com.vyoog.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.integration.IntegrationConnection;
import com.vyoog.integration.IntegrationService;
import com.vyoog.integration.connector.ConnectorExecutor;
import com.vyoog.integration.connector.ConnectorOperation;
import com.vyoog.integration.connector.ConnectorResult;
import com.vyoog.integration.planning.PlanningConnector;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.portfolio.Application;
import com.vyoog.portfolio.ApplicationRepository;
import com.vyoog.portfolio.Capability;
import com.vyoog.portfolio.CapabilityRepository;
import com.vyoog.portfolio.Product;
import com.vyoog.portfolio.ProductRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * VYB-0837: the {@code multipart/form-data} payload {@link BriefPushService} builds — product/app/capability
 * names, the app-vs-capability {@code level}, and the brief's markdown as a real file part.
 *
 * <p>VYB-0916: sending is the connector framework's now, so this captures the operation handed to it
 * instead of running a local HTTP server, and asserts the same bytes the receiver has always been sent.
 * That the request carries the signature and the API key, and goes out over real HTTP, is
 * {@code PlanningPushIT}. No Postgres here: every repository and the executor are mocks.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BriefPushServiceMultipartTest {

    @Mock BriefRepository briefs;
    @Mock ApplicationRepository applications;
    @Mock ProductRepository products;
    @Mock CapabilityRepository capabilities;
    @Mock IntegrationService integrations;
    @Mock AuditService audit;
    @Mock ConnectorExecutor connectors;
    @Mock IntegrationConnection connection;
    @Mock JdbcTemplate jdbc;

    BriefPushService service;
    UUID briefId, appId, productId, capId, actor;

    @BeforeEach
    void setUp() {
        service = new BriefPushService(briefs, applications, products, capabilities, integrations,
            new PlanningConnector(), connectors, audit, new ObjectMapper(), jdbc);

        briefId = UUID.randomUUID();
        appId = UUID.randomUUID();
        productId = UUID.randomUUID();
        capId = UUID.randomUUID();
        actor = UUID.randomUUID();

        configure("{\"pushUrl\":\"https://planning.example.test/push\",\"apiKey\":\"test-api-key\"}");
        when(connection.getWebhookSecret()).thenReturn("test-secret");
        when(integrations.get("planning")).thenReturn(connection);
        when(connectors.execute(any())).thenReturn(
            new ConnectorResult(ConnectorResult.Outcome.SUCCEEDED, 1, 200, null, UUID.randomUUID()));

        Application application = mock(Application.class);
        when(application.getName()).thenReturn("Vyoog Web");
        when(application.getProductId()).thenReturn(productId);
        when(applications.findById(appId)).thenReturn(Optional.of(application));

        Product product = mock(Product.class);
        when(product.getName()).thenReturn("Nirvaham");
        when(products.findById(productId)).thenReturn(Optional.of(product));

        Brief brief = new Brief(appId, BriefTarget.HUMAN, UUID.randomUUID(), "# A brief\n\nSome markdown.", actor);
        ReflectionTestUtils.setField(brief, "id", briefId); // Hibernate-assigned in reality; set directly for the mock
        when(briefs.findById(briefId)).thenReturn(Optional.of(brief));
    }

    private void configure(String json) {
        when(connection.getConfig()).thenReturn(json);
    }

    private ConnectorOperation pushed() {
        ArgumentCaptor<ConnectorOperation> sent = ArgumentCaptor.forClass(ConnectorOperation.class);
        verify(connectors).execute(sent.capture());
        return sent.getValue();
    }

    private static String bodyOf(ConnectorOperation op) {
        return new String(op.body(), StandardCharsets.UTF_8);
    }

    @Test
    void VYB0837_AC1_appWideBriefSendsLevelApplicationAndBlankCapabilityName() {
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(briefId))).thenReturn(List.of());

        var result = service.push(briefId, actor);

        assertThat(result.success()).isTrue();
        ConnectorOperation op = pushed();
        assertThat(op.contentType()).startsWith("multipart/form-data; boundary=");
        assertThat(bodyOf(op))
            .contains("name=\"projectName\"").contains("\r\n\r\nVyoog Web\r\n")
            .contains("name=\"customerName\"").contains("\r\n\r\nvyoog\r\n")
            .contains("name=\"productName\"").contains("Nirvaham")
            .contains("name=\"appName\"").contains("Vyoog Web")
            .contains("name=\"level\"").contains("APPLICATION")
            .contains("name=\"file\"; filename=\"VY-vyoog-web-implementation-brief.md\"")
            .contains("Content-Type: text/markdown")
            .contains("# A brief")
            .contains("Some markdown.")
            // The capabilityName field's value is empty, not populated with anything —
            // the double CRLF that ends its headers is followed immediately by the
            // next part's boundary, with nothing in between.
            .contains("name=\"capabilityName\"\r\n\r\n\r\n--");
    }

    @Test
    void VYB0837_AC2_capabilityScopedBriefSendsLevelCapabilityAndItsName() {
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(briefId))).thenReturn(List.of(capId));
        Capability cap = mock(Capability.class);
        when(cap.getName()).thenReturn("Lead Management");
        when(capabilities.findAllById(List.of(capId))).thenReturn(List.of(cap));

        var result = service.push(briefId, actor);

        assertThat(result.success()).isTrue();
        assertThat(bodyOf(pushed()))
            .contains("name=\"level\"").contains("\r\n\r\nCAPABILITY\r\n")
            .contains("name=\"capabilityName\"").contains("\r\n\r\nLead Management\r\n")
            .contains("name=\"projectName\"").contains("\r\n\r\nLead Management\r\n");
    }

    @Test
    void VYB0837_AC4_customerNameConfiguredOverridesTheVyoogDefault() {
        configure("{\"pushUrl\":\"https://planning.example.test/push\",\"customerName\":\"Acme Corp\"}");
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(briefId))).thenReturn(List.of());

        var result = service.push(briefId, actor);

        assertThat(result.success()).isTrue();
        assertThat(bodyOf(pushed())).contains("name=\"customerName\"").contains("\r\n\r\nAcme Corp\r\n");
    }

    @Test
    void VYB0916_AC1_everyPushIsItsOwnOperationForTheBriefOnTheBriefPushOperation() {
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(briefId))).thenReturn(List.of());

        service.push(briefId, actor);
        service.push(briefId, actor);

        ArgumentCaptor<ConnectorOperation> sent = ArgumentCaptor.forClass(ConnectorOperation.class);
        org.mockito.Mockito.verify(connectors, org.mockito.Mockito.times(2)).execute(sent.capture());
        assertThat(sent.getAllValues()).extracting(ConnectorOperation::operation).containsOnly("brief.push");
        assertThat(sent.getAllValues()).extracting(ConnectorOperation::connectionKey).containsOnly("planning");
        assertThat(sent.getAllValues().get(0).idempotencyKey()).startsWith("brief:" + briefId + ":")
            .as("a second click sends again, as it always has").isNotEqualTo(sent.getAllValues().get(1).idempotencyKey());
    }

    @Test
    void VYB0916_AC1_aFailedSendIsReportedWithItsStatusAndReason() {
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(briefId))).thenReturn(List.of());
        when(connectors.execute(any())).thenReturn(
            new ConnectorResult(ConnectorResult.Outcome.FAILED, 3, 503, "HTTP 503: overloaded", UUID.randomUUID()));

        var result = service.push(briefId, actor);

        assertThat(result.success()).isFalse();
        assertThat(result.statusCode()).isEqualTo(503);
        assertThat(result.error()).isEqualTo("HTTP 503: overloaded");
    }

    @Test
    void VYB0916_AC1_aPushThatNeverGotAnAnswerReportsStatusZero() {
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(briefId))).thenReturn(List.of());
        when(connectors.execute(any())).thenReturn(
            new ConnectorResult(ConnectorResult.Outcome.FAILED, 3, null, "could not reach the receiver: ConnectException", UUID.randomUUID()));

        var result = service.push(briefId, actor);

        assertThat(result.statusCode()).isZero();
        assertThat(result.error()).startsWith("could not reach the receiver");
    }
}
