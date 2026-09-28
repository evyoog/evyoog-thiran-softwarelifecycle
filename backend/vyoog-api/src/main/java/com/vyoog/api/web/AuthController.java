package com.vyoog.api.web;

import com.vyoog.identity.ImpersonationExchangeService;
import com.vyoog.identity.InternalSsoClient;
import com.vyoog.identity.InvalidCredentialsException;
import com.vyoog.identity.JwtPayloadUtil;
import com.vyoog.identity.KeycloakPasswordGrantService;
import com.vyoog.identity.KeycloakPasswordGrantService.TokenResult;
import com.vyoog.identity.SsoBridgeSession;
import com.vyoog.identity.SsoBridgeSessionService;
import com.vyoog.platform.RateLimiter;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Duration COOLDOWN = Duration.ofSeconds(1);
    private static final String REFRESH_COOKIE = "vyoog_rt";
    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";
    private static final String SSO_COOKIE = "vyoog_sso";

    private final KeycloakPasswordGrantService keycloakPasswordGrantService;
    private final ImpersonationExchangeService impersonationExchangeService;
    private final SsoBridgeSessionService bridgeSessionService;
    private final InternalSsoClient internalSsoClient;
    private final RateLimiter rateLimiter;

    @Value("${vyoog.auth.cookie-secure:true}")
    private boolean cookieSecure;
    @Value("${vyoog.internal.sso-cookie-domain:}")
    private String ssoCookieDomain;
    @Value("${vyoog.keycloak.ropc-client-id}")
    private String ownClientId;

    public AuthController(
            KeycloakPasswordGrantService keycloakPasswordGrantService,
            ImpersonationExchangeService impersonationExchangeService,
            SsoBridgeSessionService bridgeSessionService,
            InternalSsoClient internalSsoClient,
            RateLimiter rateLimiter) {
        this.keycloakPasswordGrantService = keycloakPasswordGrantService;
        this.impersonationExchangeService = impersonationExchangeService;
        this.bridgeSessionService = bridgeSessionService;
        this.internalSsoClient = internalSsoClient;
        this.rateLimiter = rateLimiter;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    /** No refreshToken field — see the class Javadoc for where it actually goes. */
    public record TokenResponse(String accessToken, long expiresInSeconds) {}

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest body, HttpServletResponse response) {
        // VYB-0048b: a per-username cooldown — see the original javadoc this
        // carve-out came with for why this is per-username, not per-IP.
        rateLimiter.requireNotLimited("auth-login:" + body.username(), COOLDOWN);
        TokenResult result = keycloakPasswordGrantService.login(body.username(), body.password());
        setRefreshCookie(response, result.refreshToken());

        String sub = String.valueOf(JwtPayloadUtil.decodePayload(result.accessToken()).get("sub"));
        String ssoSessionId = bridgeSessionService.create(sub, result.refreshToken());
        setSsoCookie(response, ssoSessionId);

        return new TokenResponse(result.accessToken(), result.expiresInSeconds());
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            @CookieValue(name = SSO_COOKIE, required = false) String ssoSessionId,
            HttpServletResponse response) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new InvalidCredentialsException("No session to restore — sign in again.");
        }
        // Keycloak rotates the refresh token on every use — the one just spent is
        // now invalid, so the cookie must be re-set to the new one every time.
        TokenResult result = refreshWithRightCredentials(refreshToken, ssoSessionId);
        setRefreshCookie(response, result.refreshToken());
        if (ssoSessionId != null) {
            bridgeSessionService.updateRefreshToken(ssoSessionId, result.refreshToken());
        }
        return new TokenResponse(result.accessToken(), result.expiresInSeconds());
    }

    /**
     * The one endpoint both "am I logged in" checks and the ~20s cross-tab
     * polling loop call. Three cases, in order — see eis-platform/vyg-pms/
     * vyg-ticket's identical method for the full reasoning:
     *  1. Own refresh cookie works → normal, unchanged session.
     *  2. No own cookie, but vyoog_sso points at a bridge row this app itself
     *     already has a local copy of (either its own real login, or a
     *     previously-cached cross-app exchange) → refresh directly, no new
     *     exchange needed.
     *  3. No own cookie, vyoog_sso points at a row only another app has →
     *     redeem it there (backend-to-backend impersonation exchange) — the
     *     actual cross-app auto-login.
     * Anything else: signed out everywhere.
     *
     * A present-but-invalid vyoog_rt cookie must NOT short-circuit straight to
     * "not authenticated" — this exact bug was found and fixed on the other
     * three apps: any real "own cookie" attempt failure must fall through to
     * case 2/3 exactly as if the cookie had never been sent at all.
     */
    @GetMapping("/session")
    public TokenResponse session(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            @CookieValue(name = SSO_COOKIE, required = false) String ssoSessionId,
            HttpServletResponse response) {
        if (refreshToken != null) {
            try {
                return refresh(refreshToken, ssoSessionId, response);
            } catch (RuntimeException ignored) {
                // Falls through below — see this method's own javadoc.
            }
        }

        if (ssoSessionId == null) {
            throw new InvalidCredentialsException("Not authenticated");
        }

        return bridgeSessionService.find(ssoSessionId)
            .map(bridge -> {
                // Impersonation-exchanged refresh tokens can't be renewed via a
                // normal refresh grant — so instead of refreshing, just redo the
                // exchange; the sub is already cached locally, no network call
                // to the other app needed.
                TokenResult result = bridge.impersonated()
                    ? impersonationExchangeService.exchangeForUser(bridge.keycloakSub(), ownClientId)
                        .orElseThrow(() -> new InvalidCredentialsException("Not authenticated"))
                    : keycloakPasswordGrantService.refresh(bridge.refreshToken());
                setRefreshCookie(response, result.refreshToken());
                bridgeSessionService.updateRefreshToken(ssoSessionId, result.refreshToken());
                return new TokenResponse(result.accessToken(), result.expiresInSeconds());
            })
            .orElseGet(() -> {
                TokenResult exchanged = internalSsoClient.redeemToken(ssoSessionId)
                    .orElseThrow(() -> new InvalidCredentialsException("Not authenticated"));
                setRefreshCookie(response, exchanged.refreshToken());
                String sub = String.valueOf(JwtPayloadUtil.decodePayload(exchanged.accessToken()).get("sub"));
                bridgeSessionService.createOrUpdate(ssoSessionId, sub, exchanged.refreshToken());
                return new TokenResponse(exchanged.accessToken(), exchanged.expiresInSeconds());
            });
    }

    /** Cheap polling target — same three-way logic as /session, just without
     * the frontend needing to distinguish "first load" from "still alive?". */
    @GetMapping("/ping")
    public TokenResponse ping(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            @CookieValue(name = SSO_COOKIE, required = false) String ssoSessionId,
            HttpServletResponse response) {
        return session(refreshToken, ssoSessionId, response);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            @CookieValue(name = SSO_COOKIE, required = false) String ssoSessionId,
            HttpServletResponse response) {
        if (refreshToken != null) {
            boolean impersonated = ssoSessionId != null && bridgeSessionService.find(ssoSessionId)
                .map(SsoBridgeSession::impersonated)
                .orElse(false);
            if (impersonated) {
                impersonationExchangeService.logout(refreshToken);
            } else {
                keycloakPasswordGrantService.logout(refreshToken);
            }
        }
        if (ssoSessionId != null) {
            bridgeSessionService.delete(ssoSessionId);
            // Impersonation-exchanged sessions are their OWN separate Keycloak
            // session (different `sid` than the originating login), so ending
            // this app's own session above never implicitly ends the others' —
            // this explicit broadcast is what actually propagates logout.
            internalSsoClient.notifyLogout(ssoSessionId);
        }
        clearRefreshCookie(response);
        clearCookie(response, SSO_COOKIE, "/", ssoCookieDomain);
    }

    /** Dispatches to whichever client actually owns this refresh token — see
     * ImpersonationExchangeService's own javadoc for why this distinction is
     * required (Keycloak ties a refresh token to whichever client requested
     * it). Defaults to this app's own client when there's no bridge row to
     * consult (the ordinary, non-cross-app case). */
    private TokenResult refreshWithRightCredentials(String refreshToken, String ssoSessionId) {
        return ssoSessionId == null
            ? keycloakPasswordGrantService.refresh(refreshToken)
            : bridgeSessionService.find(ssoSessionId)
                .map(bridge -> bridge.impersonated()
                    ? impersonationExchangeService.exchangeForUser(bridge.keycloakSub(), ownClientId)
                        .orElseThrow(() -> new InvalidCredentialsException("Not authenticated"))
                    : keycloakPasswordGrantService.refresh(refreshToken))
                .orElseGet(() -> keycloakPasswordGrantService.refresh(refreshToken));
    }

    private void setRefreshCookie(HttpServletResponse response, String refreshToken) {
        response.addHeader("Set-Cookie", ResponseCookie.from(REFRESH_COOKIE, refreshToken)
            .httpOnly(true).secure(cookieSecure).sameSite("Lax").path(REFRESH_COOKIE_PATH).build().toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie", ResponseCookie.from(REFRESH_COOKIE, "")
            .httpOnly(true).secure(cookieSecure).sameSite("Lax").path(REFRESH_COOKIE_PATH).maxAge(0).build().toString());
    }

    private void setSsoCookie(HttpServletResponse response, String ssoSessionId) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(SSO_COOKIE, ssoSessionId)
            .httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/");
        if (ssoCookieDomain != null && !ssoCookieDomain.isBlank()) {
            builder.domain(ssoCookieDomain);
        }
        response.addHeader("Set-Cookie", builder.build().toString());
    }

    private void clearCookie(HttpServletResponse response, String name, String path, String domain) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, "")
            .httpOnly(true).secure(cookieSecure).sameSite("Lax").path(path).maxAge(0);
        if (domain != null && !domain.isBlank()) {
            builder.domain(domain);
        }
        response.addHeader("Set-Cookie", builder.build().toString());
    }
}
