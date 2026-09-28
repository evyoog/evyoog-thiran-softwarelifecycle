package com.vyoog.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * VYB-0721: every request gets a request id — the caller's own {@code X-Request-Id}
 * if it sent one (so a client-side trace and this system's audit trail line up),
 * otherwise a fresh one minted here. Set as a request attribute for {@link
 * HttpRequestContext} to read and echoed on the response — {@code SecurityConfig}
 * already lists {@code X-Request-Id} as an exposed CORS header; this is what actually
 * puts a value there for that header to expose.
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = "vyoog.requestId";
    private static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        chain.doFilter(request, response);
    }
}
