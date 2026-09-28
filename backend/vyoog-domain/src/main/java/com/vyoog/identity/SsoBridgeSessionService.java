package com.vyoog.identity;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The cross-app "already logged in" marker (see SsoBridgeSession's own
 * javadoc for why this uses plain JDBC against a fixed `public` schema
 * instead of a Flyway/JPA-managed entity). The id handed to the browser is a
 * random opaque string, never a Keycloak token.
 */
@Service
public class SsoBridgeSessionService {

    /** Generous window: this only gates how long the *other* apps can silently
     * pick up the session, not how long this app's own login lasts (that's
     * governed by the real refresh-token cookie, refreshed independently). */
    private static final Duration BRIDGE_LIFETIME = Duration.ofHours(12);

    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public SsoBridgeSessionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Real login via this app's own ROPC client — refreshToken belongs to it. */
    public String create(String keycloakSub, String refreshToken) {
        return createOrUpdate(generateId(), keycloakSub, refreshToken, false);
    }

    /**
     * Caches this app's OWN copy of a bridge session under an id that was
     * actually minted by another app, after redeeming it via
     * ImpersonationExchangeService — so a later visit here can refresh
     * directly instead of exchanging again every time. impersonated=true
     * always here, since anything reaching this overload came from an
     * exchange, never a real login.
     */
    public String createOrUpdate(String id, String keycloakSub, String refreshToken) {
        return createOrUpdate(id, keycloakSub, refreshToken, true);
    }

    private String createOrUpdate(String id, String keycloakSub, String refreshToken, boolean impersonated) {
        Instant now = Instant.now();
        List<Long> existing = jdbc.query(
            "SELECT 1 FROM public.vyoog_requirement_sso_bridge_session WHERE id = ?",
            (rs, i) -> 1L, id);
        if (existing.isEmpty()) {
            jdbc.update(
                "INSERT INTO public.vyoog_requirement_sso_bridge_session " +
                "(id, keycloak_sub, refresh_token, impersonated, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, keycloakSub, refreshToken, impersonated, Timestamp.from(now), Timestamp.from(now.plus(BRIDGE_LIFETIME)));
        } else {
            jdbc.update(
                "UPDATE public.vyoog_requirement_sso_bridge_session " +
                "SET keycloak_sub = ?, refresh_token = ?, impersonated = ?, expires_at = ? WHERE id = ?",
                keycloakSub, refreshToken, impersonated, Timestamp.from(now.plus(BRIDGE_LIFETIME)), id);
        }
        return id;
    }

    public Optional<SsoBridgeSession> find(String sessionId) {
        List<SsoBridgeSession> rows = jdbc.query(
            "SELECT id, keycloak_sub, refresh_token, impersonated FROM public.vyoog_requirement_sso_bridge_session " +
            "WHERE id = ? AND expires_at > now()",
            (rs, i) -> new SsoBridgeSession(
                rs.getString("id"), rs.getString("keycloak_sub"), rs.getString("refresh_token"), rs.getBoolean("impersonated")),
            sessionId);
        return rows.stream().findFirst();
    }

    public void updateRefreshToken(String sessionId, String newRefreshToken) {
        jdbc.update("UPDATE public.vyoog_requirement_sso_bridge_session SET refresh_token = ? WHERE id = ?",
            newRefreshToken, sessionId);
    }

    public void delete(String sessionId) {
        jdbc.update("DELETE FROM public.vyoog_requirement_sso_bridge_session WHERE id = ?", sessionId);
    }

    private String generateId() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
