package com.vyoog.integration.connector;

import java.net.http.HttpRequest;

/**
 * VYB-0913: the ways a connector proves itself to the system it sends to. A connection lists one or
 * more in its {@code config.auth}; each adds its own header, so a receiver that wants both a key and
 * a signature (the existing planning push sends both) gets both.
 */
public enum ConnectorAuth {

    /** Deliberately unauthenticated. It must be written down: an absent {@code auth} is "not configured". */
    NONE,
    /** {@code <apiKeyHeader>: <apiKey>}, header name from {@code config.apiKeyHeader}, default {@code X-API-Key}. */
    API_KEY,
    /** {@code Authorization: Bearer <bearerToken>}. */
    BEARER,
    /** {@code X-Vyoog-Signature}: hex HMAC-SHA256 of the exact body bytes, keyed by the connection's shared secret. */
    HMAC_SIGNATURE;

    public static final String SIGNATURE_HEADER = "X-Vyoog-Signature";

    void apply(HttpRequest.Builder request, ConnectorConfig config, byte[] body) {
        switch (this) {
            case NONE -> { }
            case API_KEY -> request.header(config.apiKeyHeader(), config.apiKey());
            case BEARER -> request.header("Authorization", "Bearer " + config.bearerToken());
            case HMAC_SIGNATURE -> request.header(SIGNATURE_HEADER,
                com.vyoog.integration.WebhookSignatureVerifier.sign(config.hmacSecret(), body));
        }
    }
}
