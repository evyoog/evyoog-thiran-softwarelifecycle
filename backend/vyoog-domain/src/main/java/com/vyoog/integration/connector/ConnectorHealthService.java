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
    private final ConnectorSyncLog syncLog;
    private final ObjectMapper json;

    public ConnectorHealthService(IntegrationService integrations, ConnectorSyncLog syncLog, ObjectMapper json) {
        this.integrations = integrations;
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

    private ConnectorHealth of(IntegrationConnection conn) {
        String problem = ConnectorConfig.problem(conn, json).orElse(null);
        ConnectorHealth.State state;
        if (conn.isDegraded()) state = ConnectorHealth.State.DEGRADED;
        else if (conn.isConnected() && problem == null) state = ConnectorHealth.State.HEALTHY;
        else state = ConnectorHealth.State.NOT_CONNECTED;
        return new ConnectorHealth(conn.getKey(), state, problem, conn.getFailureCount(), conn.getLastError(),
            conn.getLastErrorAt(), syncLog.lastSuccess(conn.getKey()).orElse(null));
    }
}
