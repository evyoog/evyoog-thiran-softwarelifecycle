package com.vyoog.integration.connector;

import java.util.regex.Pattern;

/**
 * VYB-0913: one outbound request a connector wants made.
 *
 * <p>{@code idempotencyKey} identifies the <em>intent</em>, not the attempt: the same value on
 * every retry (sent as the {@code Idempotency-Key} header so the receiver can recognise a repeat)
 * and the key by which Vyoog refuses to send an operation that already succeeded. Derive it from
 * what is being sent, such as {@code "brief:<id>:<revision>"}, when a repeat should be suppressed.
 * When every user action must send (a "push" button that has always sent each time), use one fresh
 * random key <em>per action</em>: its retries still share it, so a receiver can drop a duplicate of a
 * retry, but two actions are two operations.
 *
 * <p>Everything that ends up in a header or a URL is checked here, so a connector bug or hostile
 * data cannot inject a header line or point the request at another host.
 */
public record ConnectorOperation(
    String connectionKey,
    String operation,
    String idempotencyKey,
    String method,
    String path,
    String contentType,
    byte[] body) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:@/=+-]{1,200}");
    private static final Pattern PATH = Pattern.compile("(/[A-Za-z0-9._~!$&'()*+,;=:@%-]*)*");
    private static final Pattern CONTENT_TYPE =
        Pattern.compile("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+(; ?[A-Za-z0-9._-]+=[\"A-Za-z0-9._-]+)*");

    public ConnectorOperation {
        require(connectionKey != null && !connectionKey.isBlank(), "connectionKey is required");
        require(operation != null && NAME.matcher(operation).matches(), "operation must be 1-100 of A-Z a-z 0-9 . _ : -");
        require(idempotencyKey != null && KEY.matcher(idempotencyKey).matches(),
            "idempotencyKey must be 1-200 of A-Z a-z 0-9 . _ : @ / = + -");
        method = method == null ? "POST" : method;
        require(java.util.Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method), "unsupported method " + method);
        path = path == null ? "" : path;
        require(PATH.matcher(path).matches() && !path.startsWith("//"), "path must be empty or start with a single /");
        contentType = contentType == null ? "application/json" : contentType;
        require(CONTENT_TYPE.matcher(contentType).matches(), "invalid contentType");
        body = body == null ? new byte[0] : body;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }

    /** A POST of a JSON body to {@code path}. */
    public static ConnectorOperation postJson(String connectionKey, String operation, String idempotencyKey,
                                              String path, String json) {
        return new ConnectorOperation(connectionKey, operation, idempotencyKey, "POST", path, "application/json",
            json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
