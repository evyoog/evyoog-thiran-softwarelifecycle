package com.vyoog.integration.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.integration.IntegrationConnection;
import com.vyoog.integration.IntegrationService;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * VYB-0913: the health state of each connection. It adds no state of its own: the counters live on
 * the registry row, which {@link ConnectorExecutor} updates after every operation, and the last
 * success is read from the sync log. A connection is DEGRADED after
 * {@link IntegrationConnection#DEGRADE_AFTER_FAILURES} failed operations in a row (the existing
 * registry rule, VYB-0743) and is HEALTHY again as soon as one succeeds.
 */
@Service
public class ConnectorHealthService {

    private final IntegrationService integrations;
    private final ConnectorRegistry connectors;
    private final ConnectorSyncLog syncLog;
    private final ObjectMapper json;

    public ConnectorHealthService(IntegrationService integrations, ConnectorRegistry connectors, ConnectorSyncLog syncLog,
                                  ObjectMapper json) {
        this.integrations = integrations;
        this.connectors = connectors;
        this.syncLog = syncLog;
        this.json = json;
    }

    public ConnectorHealth health(String connectionKey) {
        return of(integrations.get(connectionKey));
    }

    /** Every registered connection, outbound or not: an inbound-only one reports NOT_CONNECTED until it is used. */
    public List<ConnectorHealth> all() {
        return integrations.all().stream().map(this::of)
            .sorted(java.util.Comparator.comparing(ConnectorHealth::connectionKey)).toList();
    }

    /** VYB-0917: every registered connection with what the health screen shows for it. Sorted by key. */
    public List<ConnectorStatus> statuses() {
        return integrations.all().stream().map(conn -> {
            Connector connector = connectors.forConnection(conn.getKey()).orElse(null);
            return new ConnectorStatus(of(conn), conn.getOwns(), conn.getDirection().name(),
                connector == null ? null : connector.description(),
                connector == null ? java.util.Set.of() : new java.util.TreeSet<>(connector.operations()),
                syncLog.recent(conn.getKey(), 1).stream().findFirst().orElse(null));
        }).sorted(java.util.Comparator.comparing(s -> s.health().connectionKey())).toList();
    }

    /** Newest first. The limit is kept between 1 and {@link #MAX_SYNC_LOG}.
     * @throws java.util.NoSuchElementException if no such connection is registered */
    public List<ConnectorSyncLog.Entry> syncLog(String connectionKey, int limit) {
        integrations.get(connectionKey); // 404 for an unknown key rather than an empty list
        return syncLog.recent(connectionKey, Math.max(1, Math.min(limit, MAX_SYNC_LOG)));
    }

    public static final int MAX_SYNC_LOG = 100;

    /**
     * The outbound rules (a base URL, an auth scheme, a secret) apply only to a connection that sends. An
     * INBOUND-only connection (a webhook source such as the CI or HR system) is judged by whether it is
     * connected and not degraded, and "not configured" does not apply to it. Its last success is when its last
     * verified delivery arrived; an outbound one's is its last successful send; a BOTH connection has either.
     */
    private ConnectorHealth of(IntegrationConnection conn) {
        boolean sends = conn.getDirection() != IntegrationConnection.Direction.INBOUND;
        boolean receives = conn.getDirection() != IntegrationConnection.Direction.OUTBOUND;
        String problem = !sends ? null
            : ConnectorConfig.problem(conn, json, connectors.forConnection(conn.getKey()).orElse(null)).orElse(null);
        ConnectorHealth.State state;
        if (conn.isDegraded()) state = ConnectorHealth.State.DEGRADED;
        else if (conn.isConnected() && problem == null) state = ConnectorHealth.State.HEALTHY;
        else state = ConnectorHealth.State.NOT_CONNECTED;
        java.time.Instant lastSuccess = sends ? syncLog.lastSuccess(conn.getKey()).orElse(null) : null;
        if (receives) {
            java.time.Instant delivery = syncLog.lastDelivery(conn.getKey()).orElse(null);
            if (delivery != null && (lastSuccess == null || delivery.isAfter(lastSuccess))) lastSuccess = delivery;
        }
        return new ConnectorHealth(conn.getKey(), state, problem, conn.getFailureCount(), conn.getLastError(),
            conn.getLastErrorAt(), lastSuccess);
    }
}
