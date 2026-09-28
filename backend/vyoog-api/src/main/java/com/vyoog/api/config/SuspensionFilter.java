package com.vyoog.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.platform.config.AppConfigService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * VYB-0731: registered as a plain {@code @Component} filter, which Spring Boot places
 * after the security filter chain by default — so a request's bearer token is already
 * validated (authentication still succeeds, per AC1) by the time this ever runs; this
 * only decides whether the now-authenticated caller gets any further. AC2: this reads
 * {@code app_config.suspended} and nothing else — no data row is touched by suspending
 * or resuming.
 */
@Component
public class SuspensionFilter extends OncePerRequestFilter {

    private final AppConfigService config;
    private final ObjectMapper json;

    public SuspensionFilter(AppConfigService config, ObjectMapper json) {
        this.config = config;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        // Health checks and the settings endpoints that resume service must still work while suspended.
        boolean exempt = path.startsWith("/actuator/") || path.startsWith("/api/v1/settings/suspend");
        if (!exempt && config.isSuspended()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/problem+json");
            response.getWriter().write(json.writeValueAsString(Map.of(
                "type", "https://vyoog.dev/problems/suspended",
                "title", "This deployment is suspended",
                "status", 503,
                "detail", config.suspendedReason() == null ? "No reason recorded" : config.suspendedReason())));
            return;
        }
        chain.doFilter(request, response);
    }
}
