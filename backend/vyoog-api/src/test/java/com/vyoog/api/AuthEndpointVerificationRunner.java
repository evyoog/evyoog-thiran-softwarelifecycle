package com.vyoog.api;

import com.vyoog.api.web.AuthController;
import com.vyoog.identity.InvalidCredentialsException;
import com.vyoog.identity.KeycloakPasswordGrantService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VYB-0048b/VYB-0800: the username/password login endpoint, and the HttpOnly
 * refresh-token cookie that makes a page reload survive, proven against a real
 * HTTP round trip — a throwaway local server standing in for Keycloak's token
 * endpoint (same technique Session16VerificationRunner used for the outbound
 * signals push: a real listener on localhost, not a mocked HTTP client), started
 * separately — see the class-level Javadoc on how to run it. Same
 * explicit-name-only convention as the other *VerificationRunner classes:
 * invisible to {@code mvn test}/{@code mvn verify}.
 *
 * <pre>
 * # 1. python3 /tmp/.../mock_keycloak.py &amp;  (listens on 127.0.0.1:9998)
 * # 2.
 * DB_URL=... DB_USER=... DB_PASSWORD=... \
 * KEYCLOAK_TOKEN_URI=http://127.0.0.1:9998/token \
 * KEYCLOAK_ROPC_CLIENT_ID=verify-ropc-client \
 * KEYCLOAK_ROPC_CLIENT_SECRET=verify-ropc-secret \
 *   mvn -pl vyoog-api -am test -Dtest=AuthEndpointVerificationRunner \
 *   -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@SpringBootTest
class AuthEndpointVerificationRunner {

    @Autowired KeycloakPasswordGrantService grants;
    @Autowired AuthController controller;

    @Test
    void realCredentialsExchangeForRealTokensAgainstARealHttpServer() {
        var result = grants.login("gooduser", "goodpass");
        System.out.println("[verify] login result: accessToken=" + result.accessToken()
            + " refreshToken=" + result.refreshToken() + " expiresIn=" + result.expiresInSeconds());
        assertThat(result.accessToken()).isEqualTo("fake-access-token-for-gooduser");
        assertThat(result.refreshToken()).isEqualTo("fake-refresh-token-for-gooduser");
        assertThat(result.expiresInSeconds()).isEqualTo(300);

        var refreshed = grants.refresh(result.refreshToken());
        System.out.println("[verify] refresh result: accessToken=" + refreshed.accessToken());
        assertThat(refreshed.accessToken()).isEqualTo("fake-access-token-renewed");
    }

    @Test
    void wrongPasswordIsRejectedWithKeycloaksOwnReason() {
        assertThatThrownBy(() -> grants.login("gooduser", "WRONG"))
            .isInstanceOf(InvalidCredentialsException.class)
            .hasMessage("Invalid user credentials");
    }

    @Test
    void controllerEndToEndReturnsTheSameShapeAFrontendWouldParseAndSetsAnHttpOnlyRefreshCookie() {
        // Through AuthController itself, not the service directly — proves the
        // @RequestBody/record mapping and the RFC 9457 error path both work, not
        // just the HTTP client underneath them.
        var loginResponse = new MockHttpServletResponse();
        var body = controller.login(new AuthController.LoginRequest("gooduser", "goodpass"), loginResponse);
        assertThat(body.accessToken()).isEqualTo("fake-access-token-for-gooduser");
        assertThat(body.expiresInSeconds()).isEqualTo(300);

        // VYB-0800: the refresh token is never in the JSON body — only in the cookie.
        Cookie cookie = loginResponse.getCookie("vyoog_rt");
        System.out.println("[verify] refresh cookie: httpOnly=" + (cookie != null && cookie.isHttpOnly())
            + " secure=" + (cookie != null && cookie.getSecure()) + " path=" + (cookie == null ? null : cookie.getPath()));
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("fake-refresh-token-for-gooduser");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");

        // A different, never-valid username — avoids tripping the per-username
        // rate-limit cooldown against the call just made above for "gooduser".
        assertThatThrownBy(() -> controller.login(new AuthController.LoginRequest("nosuchuser", "whatever"), new MockHttpServletResponse()))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refreshReadsTheCookieNotARequestBodyAndRotatesIt() {
        // No cookie at all, and no cross-app vyoog_sso bridge cookie either —
        // the honest "sign in again" refusal, not a silent 500.
        assertThatThrownBy(() -> controller.refresh(null, null, new MockHttpServletResponse()))
            .isInstanceOf(InvalidCredentialsException.class)
            .hasMessage("No session to restore — sign in again.");

        var refreshResponse = new MockHttpServletResponse();
        var body = controller.refresh("fake-refresh-token-for-gooduser", null, refreshResponse);
        assertThat(body.accessToken()).isEqualTo("fake-access-token-renewed");

        // Keycloak rotates refresh tokens on every use — the cookie must carry the
        // NEW one afterward, not the one just spent.
        Cookie rotated = refreshResponse.getCookie("vyoog_rt");
        assertThat(rotated).isNotNull();
        assertThat(rotated.getValue()).isNotEqualTo("fake-refresh-token-for-gooduser");
    }

    @Test
    void logoutClearsTheRefreshCookie() {
        // No SSO bridge cookie either — this is the plain, non-cross-app path.
        var response = new MockHttpServletResponse();
        controller.logout(null, null, response);
        Cookie cleared = response.getCookie("vyoog_rt");
        System.out.println("[verify] logout cookie maxAge=" + (cleared == null ? null : cleared.getMaxAge()));
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
    }
}
