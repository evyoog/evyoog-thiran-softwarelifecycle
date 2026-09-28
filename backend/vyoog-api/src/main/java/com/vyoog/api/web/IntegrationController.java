package com.vyoog.api.web;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.integration.IntegrationConnection;
import com.vyoog.integration.IntegrationService;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0740/0743/0756: the registry — VYB-0741's actual inbound verification lives in {@link WebhookController}. */
@RestController
@RequestMapping("/api/v1/integrations")
public class IntegrationController {

    private final IntegrationService integrations;
    private final PrincipalGuard guard;

    public IntegrationController(IntegrationService integrations, PrincipalGuard guard) {
        this.integrations = integrations;
        this.guard = guard;
    }

    public record IntegrationView(
        String key, boolean connected, String owns, String direction,
        int failureCount, String lastError, String lastErrorAt, boolean degraded, boolean webhookConfigured,
        String config) {}

    private static IntegrationView toView(IntegrationConnection c) {
        return new IntegrationView(c.getKey(), c.isConnected(), c.getOwns(), c.getDirection().name(),
            c.getFailureCount(), c.getLastError(), c.getLastErrorAt() == null ? null : c.getLastErrorAt().toString(),
            c.isDegraded(), c.getWebhookSecret() != null, c.getConfig());
    }

    /** VYB-0756 AC1: counts derived from this same list on the frontend, never a separate hardcoded figure. */
    @GetMapping
    public List<IntegrationView> all() {
        return integrations.all().stream().map(IntegrationController::toView).toList();
    }

    /** VYB-0839: {@code owns}/{@code direction} are optional — null means "leave as is", same shape as {@code webhookSecret} already had. */
    public record SetConnected(boolean connected, String webhookSecret, String owns, String direction) {}

    @PutMapping("/{key}")
    public IntegrationView setConnected(@PathVariable String key, @RequestBody SetConnected body,
                                         @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        integrations.setConnected(key, body.connected(), body.webhookSecret());
        IntegrationConnection.Direction direction = body.direction() == null ? null
            : IntegrationConnection.Direction.valueOf(body.direction());
        IntegrationConnection updated = integrations.updateDetails(key, body.owns(), direction);
        return toView(updated);
    }

    public record CreateConnection(String key, String owns, String direction) {}

    /** VYB-0839: beyond V007's four seeded rows — creates a row only, wires up nothing on its own. */
    @PostMapping
    public IntegrationView create(@RequestBody CreateConnection body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        IntegrationConnection.Direction direction = body.direction() == null ? null
            : IntegrationConnection.Direction.valueOf(body.direction());
        return toView(integrations.create(body.key(), body.owns(), direction));
    }

    public record SetConfig(String configJson) {}

    /** VYB-0465/0507: where "planning"'s push URL (etc.) actually gets set — the {@code config} column never had a setter until now. */
    @PutMapping("/{key}/config")
    public IntegrationView setConfig(@PathVariable String key, @RequestBody SetConfig body,
                                      @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        return toView(integrations.setConfig(key, body.configJson()));
    }
}
