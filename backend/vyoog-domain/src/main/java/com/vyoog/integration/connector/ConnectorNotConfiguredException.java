package com.vyoog.integration.connector;

/**
 * VYB-0913: the connection is not set up well enough to send anything (no base URL, a secret
 * missing, plain http to a remote host). Thrown before any request, and recorded nowhere as a
 * failure: an unconfigured connection is "not connected", not a broken one (Principle 8), and must
 * not push a healthy connection towards degraded. The message names the reason and never a secret.
 */
public class ConnectorNotConfiguredException extends RuntimeException {

    private final String connectionKey;

    public ConnectorNotConfiguredException(String connectionKey, String reason) {
        super("Connection \"" + connectionKey + "\" is not configured: " + reason);
        this.connectionKey = connectionKey;
    }

    public String connectionKey() {
        return connectionKey;
    }
}
