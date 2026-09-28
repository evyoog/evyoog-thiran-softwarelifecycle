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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Mints a token for another app's own client, for a user already known to be
 * recently authenticated (via the vyoog_sso bridge — see
 * SsoBridgeSessionService), with no password and no Keycloak UI.
 *
 * Uses Keycloak's IMPERSONATION exchange: authenticate as the shared trusted
 * confidential "eVyoog" client, pass requested_subject (a Keycloak user id)
 * and audience (the target app's own client). This is the SAME "eVyoog"
 * client already used for this app's own ROPC login (see D8 in
 * docs/DECISIONS.md) and as the impersonation broker for eis-platform,
 * vyg-pms, and vyg-ticket — no new Keycloak client was registered for this
 * app. Its refresh token is not renewable via a normal refresh grant (proven
 * live on the other three apps' integrations), so callers never refresh an
 * exchanged token — they call exchangeForUser() again instead (cheap: no
 * password, no network call to the other app, since the sub is already
 * cached locally — see SsoBridgeSession.impersonated).
 */
@Service
public class ImpersonationExchangeService {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationExchangeService.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;

    public ImpersonationExchangeService(ObjectMapper json) {
        this.json = json;
    }

    @Value("${vyoog.keycloak.token-uri}")
    private String tokenUri;

    @Value("${vyoog.keycloak.end-session-uri:}")
    private String endSessionUri;

    @Value("${vyoog.internal.impersonation-client-id:eVyoog}")
    private String impersonationClientId;

    @Value("${vyoog.internal.impersonation-client-secret}")
    private String impersonationClientSecret;

    public Optional<KeycloakPasswordGrantService.TokenResult> exchangeForUser(String keycloakSub, String targetClientId) {
        return requestToken(Map.of(
            "grant_type", "urn:ietf:params:oauth:grant-type:token-exchange",
            "requested_subject", keycloakSub,
            "audience", targetClientId
        ), "impersonation exchange for target client " + targetClientId);
    }

    /** Ends the impersonation-created session — via eVyoog's own credentials.
     * This is a genuinely SEPARATE Keycloak session from whichever real login
     * originally created the bridge, so ending it here does NOT end the other
     * apps' own real sessions — see InternalSsoController's own
     * /internal/sso/logout, which is what makes logout actually propagate
     * despite that. Best-effort: never throws. */
    public void logout(String refreshToken) {
        if (endSessionUri == null || endSessionUri.isBlank()) return;
        Map<String, String> form = new HashMap<>();
        form.put("client_id", impersonationClientId);
        form.put("client_secret", impersonationClientSecret);
        form.put("refresh_token", refreshToken);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endSessionUri))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
            .build();
        try {
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("[auth] Unable to reach Keycloak to end impersonated session: {}", e.getMessage());
        }
    }

    private Optional<KeycloakPasswordGrantService.TokenResult> requestToken(Map<String, String> form, String opDescription) {
        Map<String, String> withAuth = new HashMap<>(form);
        withAuth.put("client_id", impersonationClientId);
        withAuth.put("client_secret", impersonationClientSecret);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(tokenUri))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(encode(withAuth)))
            .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("[auth] Unable to reach Keycloak for {}: {}", opDescription, e.getMessage());
            return Optional.empty();
        }

        if (response.statusCode() != 200) {
            log.warn("[auth] {} failed (HTTP {}): {}", opDescription, response.statusCode(), response.body());
            return Optional.empty();
        }

        try {
            JsonNode body = json.readTree(response.body());
            return Optional.of(new KeycloakPasswordGrantService.TokenResult(
                body.get("access_token").asText(),
                body.get("refresh_token").asText(),
                body.get("expires_in").asLong()
            ));
        } catch (IOException e) {
            log.warn("[auth] Malformed response from Keycloak for {}: {}", opDescription, e.getMessage());
            return Optional.empty();
        }
    }

    private static String encode(Map<String, String> form) {
        return form.entrySet().stream()
            .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .reduce((a, b) -> a + "&" + b)
            .orElse("");
    }
}
