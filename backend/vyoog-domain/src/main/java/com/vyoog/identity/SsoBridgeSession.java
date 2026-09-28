package com.vyoog.identity;

/**
 * Plain row from `public.vyoog_requirement_sso_bridge_session` — deliberately
 * NOT a JPA @Entity. This app's own schema (`vyg_requirement`) is Flyway-owned
 * and irrelevant here; this table is cross-app identity data, not this app's
 * business data, so it lives in `public` and is read/written with plain JDBC
 * instead (see SsoBridgeSessionService). It gets its OWN table rather than
 * reusing `vyg-pms`'s `public.sso_bridge_session` (even though both share the
 * same physical RDS database) — confirmed live in vyg-ticket's own
 * integration that sharing the literal table corrupts the protocol: one app's
 * own-login row gets misread by another as its own cached session, and it
 * then tries to refresh that OTHER app's refresh token under its own
 * Keycloak client credentials, which Keycloak correctly rejects.
 *
 * impersonated: true when refreshToken was minted via the shared "eVyoog"
 * impersonation client (see ImpersonationExchangeService) rather than a real
 * login through this app's own ROPC client — Keycloak refresh tokens are
 * bound to whichever client requested them, so this flag decides which
 * credentials to refresh/logout with.
 */
public record SsoBridgeSession(String id, String keycloakSub, String refreshToken, boolean impersonated) {
}
