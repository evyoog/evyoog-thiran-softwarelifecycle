package com.vyoog.platform.audit;

import java.util.Optional;

/**
 * Deliberately not a Spring bean — {@code vyoog-api} registers the one real {@link
 * RequestContext} implementation ({@code HttpRequestContext}); a second bean here
 * would just create an ambiguous-wiring bug waiting to happen. This exists for tests
 * and any future non-web caller (a scheduled job with no request to describe) that
 * constructs {@link AuditService} directly.
 */
public final class NoRequestContext implements RequestContext {

    public static final NoRequestContext INSTANCE = new NoRequestContext();

    @Override
    public Optional<String> requestId() { return Optional.empty(); }

    @Override
    public Optional<String> remoteAddress() { return Optional.empty(); }
}
