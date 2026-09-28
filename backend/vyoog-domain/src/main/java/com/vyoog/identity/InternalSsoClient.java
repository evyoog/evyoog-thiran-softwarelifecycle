package com.vyoog.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Calls another app's own backend-only /internal/sso/token endpoint to redeem
 * a `vyoog_sso` bridge-session id for a token already scoped to this app's
 * own Keycloak client, for the same user — backend-to-backend only, guarded
 * by a shared secret, never reachable from a browser.
 *
 * With four apps now in the SSO mesh (this one, eis-platform, vyg-pms,
 * vyg-ticket), a bridge session could have originated from any of the other
 * three, and this app has no way to tell which just from the opaque id — so
 * redeemToken() tries every configured partner in turn and returns the first
 * one that actually has that session (a 404 from a partner that doesn't hold
 * it is the normal, expected case, not an error). notifyLogout() broadcasts
 * to ALL partners, since any of them might be holding their own cached copy.
 */
@Service
public class InternalSsoClient {

    private static final Logger log = LoggerFactory.getLogger(InternalSsoClient.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    private final List<String> partnerBackendUrls;
    private final String sharedSecret;
    private final String ownClientId;

    public InternalSsoClient(
            ObjectMapper json,
            @Value("${vyoog.internal.partner-backend-urls}") String partnerBackendUrlsCsv,
            @Value("${vyoog.internal.sso-shared-secret}") String sharedSecret,
            @Value("${vyoog.keycloak.ropc-client-id}") String ownClientId) {
        this.json = json;
        this.partnerBackendUrls = Arrays.stream(partnerBackendUrlsCsv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
        this.sharedSecret = sharedSecret;
        this.ownClientId = ownClientId;
    }

    public Optional<KeycloakPasswordGrantService.TokenResult> redeemToken(String ssoSessionId) {
        for (String partnerUrl : partnerBackendUrls) {
            Optional<KeycloakPasswordGrantService.TokenResult> result = redeemFrom(partnerUrl, ssoSessionId);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    private Optional<KeycloakPasswordGrantService.TokenResult> redeemFrom(String partnerUrl, String ssoSessionId) {
        try {
            String body = json.writeValueAsString(Map.of(
                "ssoSessionId", ssoSessionId,
                "targetClientId", ownClientId));
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(partnerUrl + "/internal/sso/token"))
                .header("Content-Type", "application/json")
                .header("X-Internal-Sso-Secret", sharedSecret)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            JsonNode node = json.readTree(response.body());
            return Optional.of(new KeycloakPasswordGrantService.TokenResult(
                node.get("accessToken").asText(),
                node.get("refreshToken").asText(),
                node.get("expiresInSeconds").asLong()
            ));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("[auth] Internal SSO redemption call to {} failed: {}", partnerUrl, e.getMessage());
            return Optional.empty();
        }
    }

    public void notifyLogout(String ssoSessionId) {
        for (String partnerUrl : partnerBackendUrls) {
            notifyOne(partnerUrl, ssoSessionId);
        }
    }

    private void notifyOne(String partnerUrl, String ssoSessionId) {
        try {
            String body = json.writeValueAsString(Map.of("ssoSessionId", ssoSessionId));
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(partnerUrl + "/internal/sso/logout"))
                .header("Content-Type", "application/json")
                .header("X-Internal-Sso-Secret", sharedSecret)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("[auth] Internal SSO logout notification to {} failed: {}", partnerUrl, e.getMessage());
        }
    }
}
