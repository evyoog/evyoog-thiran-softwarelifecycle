package com.vyoog.api.web;

import com.vyoog.identity.ImpersonationExchangeService;
import com.vyoog.identity.KeycloakPasswordGrantService;
import com.vyoog.identity.KeycloakPasswordGrantService.TokenResult;
import com.vyoog.identity.SsoBridgeSession;
import com.vyoog.identity.SsoBridgeSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Backend-to-backend only — never reachable from a browser (see SecurityConfig:
 * this path is permitAll'd there but every request must still carry the shared
 * secret, checked here).
 */
@RestController
@RequestMapping("/api/v1/internal/sso")
public class InternalSsoController {

    private final SsoBridgeSessionService bridgeSessionService;
    private final ImpersonationExchangeService impersonationExchangeService;
    private final KeycloakPasswordGrantService keycloakPasswordGrantService;

    public InternalSsoController(
            SsoBridgeSessionService bridgeSessionService,
            ImpersonationExchangeService impersonationExchangeService,
            KeycloakPasswordGrantService keycloakPasswordGrantService) {
        this.bridgeSessionService = bridgeSessionService;
        this.impersonationExchangeService = impersonationExchangeService;
        this.keycloakPasswordGrantService = keycloakPasswordGrantService;
    }

    @Value("${vyoog.internal.sso-shared-secret}")
    private String sharedSecret;

    public record TokenRequest(@NotBlank String ssoSessionId, @NotBlank String targetClientId) {}

    public record TokenResponse(String accessToken, String refreshToken, long expiresInSeconds) {}

    public record LogoutRequest(@NotBlank String ssoSessionId) {}

    /** Another app's own backend calls this to redeem a `vyoog_sso`
     * bridge-session id for a token already scoped to ITS OWN Keycloak
     * client, for the same user — see ImpersonationExchangeService for how,
     * and why a straight client-to-client Token Exchange doesn't work for
     * this. */
    @PostMapping("/token")
    public ResponseEntity<TokenResponse> token(
            @Valid @RequestBody TokenRequest request,
            @RequestHeader(value = "X-Internal-Sso-Secret", required = false) String providedSecret) {
        if (!secretMatches(providedSecret)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        return bridgeSessionService.find(request.ssoSessionId())
            .flatMap(session -> exchange(session, request.targetClientId()))
            .map(result -> ResponseEntity.ok(
                new TokenResponse(result.accessToken(), result.refreshToken(), result.expiresInSeconds())))
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /**
     * Another app calls this when IT logs out, so this app's own copy of the
     * same bridge session (if it has one) also gets torn down. Needed because
     * impersonation-exchanged tokens live in their OWN separate Keycloak
     * session, so ending one side's Keycloak session via the normal
     * end-session call does NOT end the others' — logout has to be explicitly
     * propagated through this bridge instead. Always 204, even if this app
     * never had a copy of that session — that's a normal, expected case, not
     * an error.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @Valid @RequestBody LogoutRequest request,
            @RequestHeader(value = "X-Internal-Sso-Secret", required = false) String providedSecret) {
        if (!secretMatches(providedSecret)) {
            throw new org.springframework.security.access.AccessDeniedException("Bad internal SSO secret");
        }

        bridgeSessionService.find(request.ssoSessionId()).ifPresent(session -> {
            if (session.impersonated()) {
                impersonationExchangeService.logout(session.refreshToken());
            } else {
                keycloakPasswordGrantService.logout(session.refreshToken());
            }
            bridgeSessionService.delete(request.ssoSessionId());
        });
    }

    private Optional<TokenResult> exchange(SsoBridgeSession session, String targetClientId) {
        return impersonationExchangeService.exchangeForUser(session.keycloakSub(), targetClientId);
    }

    private boolean secretMatches(String provided) {
        return provided != null && constantTimeEquals(provided, sharedSecret);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
