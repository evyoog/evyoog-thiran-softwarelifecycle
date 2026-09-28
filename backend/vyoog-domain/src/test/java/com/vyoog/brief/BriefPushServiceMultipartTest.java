package com.vyoog.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vyoog.integration.IntegrationConnection;
import com.vyoog.integration.IntegrationService;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.portfolio.Application;
import com.vyoog.portfolio.ApplicationRepository;
import com.vyoog.portfolio.Capability;
import com.vyoog.portfolio.CapabilityRepository;
import com.vyoog.portfolio.Product;
import com.vyoog.portfolio.ProductRepository;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * VYB-0837: the actual {@code multipart/form-data} upload {@link BriefPushService}
 * sends — product/app/capability names, the app-vs-capability {@code level}, and the
 * brief's markdown as a real file part — proven against a real (local, ephemeral) HTTP
 * server rather than only the refused-before-any-request paths {@link
 * BriefPushServiceTest} covers. No external network and no Postgres: every repository
 * is mocked, and the "planning tool" on the other end is a plain JDK {@link HttpServer}
 * running in this JVM for the duration of each test.
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
    @Mock IntegrationConnection connection;
    @Mock JdbcTemplate jdbc;

    BriefPushService service;
    HttpServer server;
    UUID briefId, appId, productId, capId, actor;
    final AtomicReference<String> capturedContentType = new AtomicReference<>();
    final AtomicReference<String> capturedBody = new AtomicReference<>();
    final AtomicReference<String> capturedApiKey = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/push", exchange -> {
            capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            capturedApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();

        service = new BriefPushService(briefs, applications, products, capabilities, integrations, audit,
            new ObjectMapper(), jdbc);

        briefId = UUID.randomUUID();
        appId = UUID.randomUUID();
        productId = UUID.randomUUID();
        capId = UUID.randomUUID();
        actor = UUID.randomUUID();

        when(connection.getConfig()).thenReturn(
            "{\"pushUrl\":\"http://localhost:" + server.getAddress().getPort() + "/push\","
                + "\"apiKey\":\"test-api-key\"}");
        when(connection.getWebhookSecret()).thenReturn("test-secret");
        when(integrations.get("planning")).thenReturn(connection);

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

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void VYB0837_AC1_appWideBriefSendsLevelApplicationAndBlankCapabilityName() {
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(briefId))).thenReturn(List.of());

        var result = service.push(briefId, actor);

        assertThat(result.success()).isTrue();
        assertThat(capturedContentType.get()).startsWith("multipart/form-data; boundary=");
        assertThat(capturedApiKey.get()).isEqualTo("test-api-key");
        assertThat(capturedBody.get())
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
        assertThat(capturedBody.get())
            .contains("name=\"level\"").contains("\r\n\r\nCAPABILITY\r\n")
            .contains("name=\"capabilityName\"").contains("\r\n\r\nLead Management\r\n")
            .contains("name=\"projectName\"").contains("\r\n\r\nLead Management\r\n");
    }

    @Test
    void VYB0837_AC3_noApiKeyConfiguredOmitsHeaderRatherThanRefusing() {
        // apiKey is specific to this one external destination, not something every
        // "planning" connection needs — unlike pushUrl/webhookSecret, its absence
        // doesn't refuse the push, it just sends the request without the header.
        when(connection.getConfig()).thenReturn(
            "{\"pushUrl\":\"http://localhost:" + server.getAddress().getPort() + "/push\"}");
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(briefId))).thenReturn(List.of());

        var result = service.push(briefId, actor);

        assertThat(result.success()).isTrue();
        assertThat(capturedApiKey.get()).isNull();
    }

    @Test
    void VYB0837_AC4_customerNameConfiguredOverridesTheVyoogDefault() {
        when(connection.getConfig()).thenReturn(
            "{\"pushUrl\":\"http://localhost:" + server.getAddress().getPort() + "/push\","
                + "\"customerName\":\"Acme Corp\"}");
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(briefId))).thenReturn(List.of());

        var result = service.push(briefId, actor);

        assertThat(result.success()).isTrue();
        assertThat(capturedBody.get()).contains("name=\"customerName\"").contains("\r\n\r\nAcme Corp\r\n");
    }
}
