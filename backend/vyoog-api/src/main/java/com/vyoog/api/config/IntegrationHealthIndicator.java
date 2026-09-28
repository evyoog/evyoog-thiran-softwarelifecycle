package com.vyoog.api.config;

import com.vyoog.integration.IntegrationConnection;
import com.vyoog.integration.IntegrationService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * VYB-0784: integration health as one of the things this deployment's own
 * {@code /actuator/health} reports — not a stand-in for {@code integration_connection}
 * itself (VYB-0756 reads that directly), but the same "unavailable is reported, never
 * silently retried" principle (VYB-0743) surfaced where an ops dashboard would
 * actually look for it.
 */
@Component
public class IntegrationHealthIndicator implements HealthIndicator {

    private final IntegrationService integrations;

    public IntegrationHealthIndicator(IntegrationService integrations) {
        this.integrations = integrations;
    }

    @Override
    public Health health() {
        var degraded = integrations.all().stream().filter(IntegrationConnection::isDegraded).toList();
        if (degraded.isEmpty()) {
            return Health.up().build();
        }
        Health.Builder builder = Health.status("DEGRADED");
        degraded.forEach(c -> builder.withDetail(c.getKey(), c.getLastError()));
        return builder.build();
    }
}
