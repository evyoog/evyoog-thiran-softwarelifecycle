package com.vyoog.api.config;

import com.vyoog.identity.AccessRule;
import com.vyoog.identity.GrantRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * VYB-0906: enforces {@link RequiresAccess} before the handler runs. Because it runs before
 * argument binding, an unauthorised caller is refused with a 403 even when the body is missing or
 * malformed, and the check cannot be skipped by a handler that forgets to call the guard. A handler
 * without the annotation is untouched (it is either guarded in its own code, open by design, or
 * still on the pending list that {@code AccessPolicyTest} tracks).
 */
@Component
public class AccessInterceptor implements HandlerInterceptor {

    private final PrincipalGuard guard;
    private final AccessScopeResolver scopes;

    public AccessInterceptor(PrincipalGuard guard, AccessScopeResolver scopes) {
        this.guard = guard;
        this.scopes = scopes;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) return true;
        RequiresAccess access = method.getMethodAnnotation(RequiresAccess.class);
        if (access == null) return true;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new GrantRequiredException("Sign in to " + access.value().description(), null);
        }
        String action = access.value().description();
        AccessRule rule = access.value();

        switch (access.scope()) {
            case NONE -> {
                if (rule == AccessRule.ADMIN) {
                    guard.requireHuman(jwt); // a service account must never reach the user lookup behind the admin check
                    guard.requireAdministrator(jwt);
                }
                else if (rule == AccessRule.PERSON) guard.requireHuman(jwt);
                else guard.requireRuleAnywhere(jwt, rule, action);
            }
            case ANYWHERE -> guard.requireRuleAnywhere(jwt, rule, action);
            case REQUIREMENT -> {
                var s = scopes.ofRequirement(pathId(request, access.idVar()));
                guard.requireAnyRoleOrAdmin(jwt, rule.roles(), s.type(), s.id(), action);
            }
            case CRITERION -> {
                var s = scopes.ofCriterion(pathId(request, access.idVar()));
                guard.requireAnyRoleOrAdmin(jwt, rule.roles(), s.type(), s.id(), action);
            }
            case BATCH -> {
                var s = scopes.ofBatch(pathId(request, access.idVar()));
                guard.requireAnyRoleOrAdmin(jwt, rule.roles(), s.type(), s.id(), action);
            }
            case CANDIDATE -> {
                var s = scopes.ofCandidate(pathId(request, access.idVar()));
                guard.requireAnyRoleOrAdmin(jwt, rule.roles(), s.type(), s.id(), action);
            }
            case ANALYSIS -> {
                var s = scopes.ofAnalysis(pathId(request, access.idVar()));
                guard.requireAnyRoleOrAdmin(jwt, rule.roles(), s.type(), s.id(), action);
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static UUID pathId(HttpServletRequest request, String variable) {
        Map<String, String> vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String raw = vars == null ? null : vars.get(variable);
        if (raw == null) throw new IllegalStateException("Path variable '" + variable + "' not found for the access rule");
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new java.util.NoSuchElementException("No such resource");
        }
    }
}
