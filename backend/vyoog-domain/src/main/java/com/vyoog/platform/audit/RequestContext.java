package com.vyoog.platform.audit;

import java.util.Optional;

/**
 * VYB-0721: the request identifier and origin address an audit event should carry —
 * an interface here so the domain layer never imports a servlet type (ArchUnit's own
 * rule); the implementation that actually reads {@code HttpServletRequest} lives in
 * {@code vyoog-api} ({@code HttpRequestContext}). Outside a real HTTP request (a
 * scheduled sweep, a test), both are legitimately absent — {@link
 * NoRequestContext} is what's used then, not a servlet-backed stub.
 */
public interface RequestContext {
    Optional<String> requestId();
    Optional<String> remoteAddress();
}
