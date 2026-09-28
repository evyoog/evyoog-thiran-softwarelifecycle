package com.vyoog.api.config;

import com.vyoog.platform.audit.RequestContext;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * VYB-0721: the one place {@code com.vyoog.platform.audit.RequestContext} actually
 * reads a servlet request — everything else (including {@link
 * com.vyoog.platform.audit.AuditService}) only ever sees the interface, so ArchUnit's
 * domain-must-not-depend-on-web rule is never at risk from audit provenance.
 * {@code request.getAttribute} rather than a header lookup for the request id — {@link
 * RequestIdFilter} is what puts it there, generating one when the caller didn't
 * already send an {@code X-Request-Id}.
 */
@Component
public class HttpRequestContext implements RequestContext {

    @Override
    public Optional<String> requestId() {
        return currentRequest().map(req -> (String) req.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    @Override
    public Optional<String> remoteAddress() {
        return currentRequest().map(jakarta.servlet.http.HttpServletRequest::getRemoteAddr);
    }

    private Optional<jakarta.servlet.http.HttpServletRequest> currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servletAttrs)) return Optional.empty();
        return Optional.ofNullable(servletAttrs.getRequest());
    }
}
