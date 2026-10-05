package com.vyoog.api.web;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.integration.connector.ConnectorHealth;
import com.vyoog.integration.connector.ConnectorHealthService;
import com.vyoog.integration.connector.ConnectorStatus;
import com.vyoog.integration.connector.ConnectorSyncLog;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * VYB-0917 (F40): what the Administration "Connector health" screen reads. Platform administrators only.
 *
 * <p>Deliberately separate from {@link IntegrationController}, whose list returns each connection's
 * {@code config}. Nothing here returns configuration, a secret, a payload or a request header: only
 * state, counts, times and the short reasons the framework already redacted when it recorded them.
 */
@RestController
@RequestMapping("/api/v1/integrations")
public class ConnectorHealthController {

    private final ConnectorHealthService health;
    private final PrincipalGuard guard;

    public ConnectorHealthController(ConnectorHealthService health, PrincipalGuard guard) {
        this.health = health;
        this.guard = guard;
    }

    /** One newest-first sync-log row. {@code error} came from the receiver (shortened, secrets removed). */
    public record ConnectorSyncEntryView(
        String id, String operation, String idempotencyKey, String status, int attempts, Integer httpStatus,
        String error, int payloadBytes, String startedAt, String finishedAt, Long durationMs) {}

    /**
     * @param state NOT_CONNECTED, HEALTHY or DEGRADED
     * @param notConfiguredReason why nothing can be sent yet, or null when the configuration is usable
     * @param connectorDescription what the code bound to this connection does; null when none is
     * @param lastSync newest sync-log row; null when nothing has ever been sent
     */
    public record ConnectorStatusView(
        String key, String state, String notConfiguredReason, String owns, String direction,
        String connectorDescription, List<String> operations, int consecutiveFailures, String lastError,
        String lastErrorAt, String lastSuccessAt, ConnectorSyncEntryView lastSync) {}

    @GetMapping("/health")
    public List<ConnectorStatusView> health(@AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        return health.statuses().stream().map(ConnectorHealthController::toView).toList();
    }

    @GetMapping("/{key}/sync-log")
    public List<ConnectorSyncEntryView> syncLog(@PathVariable String key,
                                                @RequestParam(defaultValue = "20") int limit,
                                                @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        return health.syncLog(key, limit).stream().map(ConnectorHealthController::toView).toList();
    }

    private static ConnectorStatusView toView(ConnectorStatus s) {
        ConnectorHealth h = s.health();
        return new ConnectorStatusView(h.connectionKey(), h.state().name(), h.notConfiguredReason(), s.owns(),
            s.direction(), s.connectorDescription(), List.copyOf(s.operations()), h.consecutiveFailures(),
            h.lastError(), text(h.lastErrorAt()), text(h.lastSuccessAt()),
            s.lastSync() == null ? null : toView(s.lastSync()));
    }

    private static ConnectorSyncEntryView toView(ConnectorSyncLog.Entry e) {
        Long durationMs = e.finishedAt() == null ? null : Duration.between(e.startedAt(), e.finishedAt()).toMillis();
        return new ConnectorSyncEntryView(e.id().toString(), e.operation(), e.idempotencyKey(), e.status(), e.attempts(),
            e.httpStatus(), e.error(), e.payloadBytes(), text(e.startedAt()), text(e.finishedAt()), durationMs);
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
