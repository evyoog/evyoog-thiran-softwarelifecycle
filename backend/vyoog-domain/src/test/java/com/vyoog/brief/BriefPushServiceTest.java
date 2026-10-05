package com.vyoog.brief;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.integration.IntegrationConnection;
import com.vyoog.integration.IntegrationService;
import com.vyoog.integration.connector.ConnectorExecutor;
import com.vyoog.integration.planning.PlanningConnector;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.portfolio.ApplicationRepository;
import com.vyoog.portfolio.CapabilityRepository;
import com.vyoog.portfolio.ProductRepository;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * VYB-0818: refuses with a named reason rather than a silent no-op or a generic
 * failure — Principle 8 read as "an unconfigured outbound push says so." The actual
 * successful-HTTP-push path is not unit-tested here, same as the "planning" connection's
 * other pusher ({@code SignalsExportService}) — {@code HttpClient} is a plain field, not
 * an injectable seam, and no test in this codebase spins up a local HTTP server for
 * these; the refusal paths are what a unit test can actually exercise honestly.
 *
 * <p>VYB-0916: the HTTP push itself now goes through the connector framework, so the refusals here are
 * the ones raised before anything is handed to it, and nothing is sent when one is raised. The full push,
 * over real HTTP and a real database, is {@code PlanningPushIT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BriefPushServiceTest {

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
    UUID briefId;
    UUID actor;

    @BeforeEach
    void setUp() {
        service = new BriefPushService(briefs, applications, products, capabilities, integrations,
            new PlanningConnector(), connectors, audit, new ObjectMapper(), jdbc);
        briefId = UUID.randomUUID();
        actor = UUID.randomUUID();
        Brief brief = new Brief(UUID.randomUUID(), BriefTarget.HUMAN, UUID.randomUUID(), "content", actor);
        when(briefs.findById(briefId)).thenReturn(Optional.of(brief));
        when(integrations.get("planning")).thenReturn(connection);
    }

    @Test
    void VYB0818_AC1_noBriefWithThatIdIsNotFound() {
        when(briefs.findById(briefId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.push(briefId, actor)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void VYB0818_AC2_noPushUrlConfiguredRefusesByName() {
        when(connection.getConfig()).thenReturn(null);

        assertThatThrownBy(() -> service.push(briefId, actor))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("planning")
            .hasMessageContaining("push URL");
    }

    @Test
    void VYB0818_AC3_pushUrlWithNoSharedSecretRefusesByName() {
        when(connection.getConfig()).thenReturn("{\"pushUrl\":\"https://example.test/hook\"}");
        when(connection.getWebhookSecret()).thenReturn(null);

        assertThatThrownBy(() -> service.push(briefId, actor))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("shared secret");
    }

    @Test
    void VYB0818_AC4_neitherConfiguredNorHttpEverAttemptedWhenRefused() {
        when(connection.getConfig()).thenReturn(null);

        assertThatThrownBy(() -> service.push(briefId, actor)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(audit, connectors);
        verify(integrations, never()).setConnected(anyString(), anyBoolean(), any());
        verify(integrations, never()).recordFailure(anyString(), anyString());
    }
}
