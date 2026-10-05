package com.vyoog.integration.connector;

import java.util.Set;

/**
 * VYB-0917: one row of the connector health screen: a registered connection's {@link ConnectorHealth}, what
 * the registry says it is for, the {@link Connector} bound to it (if any code is), and its latest sync.
 * Holds no configuration and no secret.
 *
 * @param connectorDescription null when no {@link Connector} bean is bound to this connection
 * @param operations empty when no {@link Connector} is bound
 * @param lastSync the newest sync-log row, or null if nothing has ever been sent through it
 */
public record ConnectorStatus(
    ConnectorHealth health,
    String owns,
    String direction,
    String connectorDescription,
    Set<String> operations,
    ConnectorSyncLog.Entry lastSync) {}
