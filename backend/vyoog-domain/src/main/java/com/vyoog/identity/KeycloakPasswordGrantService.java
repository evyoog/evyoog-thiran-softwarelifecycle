package com.vyoog.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * VYB-0048b: a username/password sign-in screen, without ever shipping a Keycloak
 * client secret to the browser. The password lives in this one request only —
 * forwarded to Keycloak's token endpoint and discarded; never logged, never
 * persisted, never returned.
 *
 * <p>The client used here is "vyg-devops-ui", a dedicated confidential client
 * (docs/DECISIONS.md D21, 2026-09-11). VYB-0048b originally called for exactly
 * this — a separate, purpose-built client, specifically to avoid extending
 * "eVyoog"'s blast radius beyond the Keycloak Admin API and vyg-pms, which
 * already share it. D8 (2026-08-12) temporarily overrode that to reuse eVyoog
 * instead; D21 reverses D8 now that the same dedicated-client pattern is
 * already in place for eis-platform, vyg-pms, and vyg-ticket's own logins —
 * read D21 before assuming this should go back to the shared client. Distinct
 * either way from "vyoog-web" (D7): that one is public/PKCE-only for the
 * Authorization Code flow, unused by this password-form flow — a client can't
 * be both public and support Direct Access Grants safely.
 */
@Service
public class KeycloakPasswordGrantService {

    private static final Logger log = LoggerFactory.getLogger(KeycloakPasswordGrantService.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;

    public KeycloakPasswordGrantService(ObjectMapper json) {
        this.json = json;
    }

    @Value("${vyoog.keycloak.token-uri}")
    private String tokenUri;

    @Value("${vyoog.keycloak.end-session-uri:}")
    private String endSessionUri;

    @Value("${vyoog.keycloak.ropc-client-id}")
    private String clientId;

    @Value("${vyoog.keycloak.ropc-client-secret}")
    private String clientSecret;

    public record TokenResult(String accessToken, String refreshToken, long expiresInSeconds) {}

    public TokenResult login(String username, String password) {
        requireConfigured();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "password");
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("username", username);
        form.put("password", password);
        return exchange(form);
    }

    public TokenResult refresh(String refreshToken) {
        requireConfigured();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("refresh_token", refreshToken);
        return exchange(form);
    }

    /**
     * D8, not D7 — the message used to cite the wrong decision and send whoever read it
     * looking for a dedicated client that D8 deliberately decided not to register.
     *
     * <p>It also reported both halves as missing when only one ever is: {@code clientId}
     * defaults to {@code eVyoog} in application.yml, so in practice the secret is the
     * part nobody has set, and saying "no client is registered" describes a problem that
     * does not exist while hiding the one that does.
     */
    private void requireConfigured() {
        boolean noClient = clientId == null || clientId.isBlank();
        boolean noSecret = clientSecret == null || clientSecret.isBlank();
        if (!noClient && !noSecret) return;

        String missing;
        if (noClient && noSecret) {
            missing = "neither KEYCLOAK_ROPC_CLIENT_ID nor KEYCLOAK_ROPC_CLIENT_SECRET is set";
        } else if (noClient) {
            missing = "KEYCLOAK_ROPC_CLIENT_ID is empty";
        } else {
            missing = "KEYCLOAK_ROPC_CLIENT_SECRET is empty (the client id is '" + clientId + "')";
        }
        throw new IllegalStateException(
            "Username/password sign-in is not configured — " + missing
                + ". The ROPC client is vyg-devops-ui, a dedicated confidential client whose secret "
                + "Vyoog does not store anywhere; it has to be supplied as an environment variable. "
                + "See docs/DECISIONS.md D21.");
    }

    private TokenResult exchange(Map<String, String> form) {
        String body = form.entrySet().stream()
            .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .reduce((a, b) -> a + "&" + b)
            .orElse("");

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(tokenUri))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            // VYB-0048b: never let the raw exception (which may echo request state)
            // reach the client; the grant type/client id are not secrets, but this
            // keeps the failure mode consistent regardless of cause.
            log.warn("[auth] Keycloak token endpoint unreachable: {}", e.getMessage());
            throw new IllegalStateException("Could not reach the identity provider — try again shortly.");
        }

        if (response.statusCode() == 200) {
            JsonNode node = readTree(response.body());
            return new TokenResult(
                node.path("access_token").asText(),
                node.path("refresh_token").asText(null),
                node.path("expires_in").asLong(0));
        }

        // VYB-0048b: Keycloak's own error_description ("Invalid user credentials",
        // "Account disabled") is safe to relay — it's exactly what a real login form
        // would say — but the raw response body isn't, so this doesn't just forward it.
        String detail = readTree(response.body()).path("error_description").asText("Invalid username or password.");
        log.info("[auth] password grant rejected: status={} reason={}", response.statusCode(), detail);
        throw new InvalidCredentialsException(detail);
    }

    /** Ends the real Keycloak session server-side — this is what makes the
     * other apps' own refresh tokens (issued for the same login) fail too,
     * since they all share the same underlying Keycloak session. Best-effort:
     * local state is cleared by the caller regardless of whether this succeeds. */
    public void logout(String refreshToken) {
        if (endSessionUri == null || endSessionUri.isBlank()) return;
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("refresh_token", refreshToken);
        String body = form.entrySet().stream()
            .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .reduce((a, b) -> a + "&" + b)
            .orElse("");
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endSessionUri))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        try {
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("[auth] Unable to reach Keycloak to end session: {}", e.getMessage());
        }
    }

    private JsonNode readTree(String body) {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            return json.getNodeFactory().objectNode();
        }
    }
}
