package com.vyoog.integration.connector;

import java.util.Set;

/**
 * VYB-0913 (F40): an outbound adapter to one external system, bound to one row of the connection
 * registry ({@code integration_connection}). It is the declaration, not the machinery: a connector
 * says which connection it uses and which operations it performs, builds each
 * {@link ConnectorOperation}, and hands it to {@link ConnectorExecutor}, which does the sending,
 * authentication, retries, idempotency, logging and health tracking the same way for every
 * connector.
 *
 * <p>Implement this as a Spring bean and it appears in {@link ConnectorRegistry}. Nothing here
 * knows any particular system: a connector for a real system (the Agile Planner, VYB-0914 onward)
 * supplies its own payloads and endpoints.
 */
public interface Connector {

    /** The {@code integration_connection.key} this connector sends through. */
    String connectionKey();

    /** One line for the people who administer connections. */
    String description();

    /** The operation names it performs, as written to the sync log (for example {@code "brief.push"}). */
    Set<String> operations();
}
