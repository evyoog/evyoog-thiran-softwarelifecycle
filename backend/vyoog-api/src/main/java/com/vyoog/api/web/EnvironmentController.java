package com.vyoog.api.web;

import com.vyoog.deployment.Deployment;
import com.vyoog.deployment.DeploymentRepository;
import com.vyoog.deployment.DeploymentService;
import com.vyoog.deployment.Environment;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** VYB-0480/0520: environments and what's been deployed to them — ingestion itself is {@code CiIngestController}. */
@RestController
@RequestMapping("/api/v1")
public class EnvironmentController {

    private final DeploymentService service;
    private final DeploymentRepository deployments;

    public EnvironmentController(DeploymentService service, DeploymentRepository deployments) {
        this.service = service;
        this.deployments = deployments;
    }

    public record CreateEnvironment(@NotBlank String name, short ordinal) {}
    public record EnvironmentView(String id, String name, short ordinal) {}
    public record DeploymentView(String id, String environmentId, String buildLabel, String deployedAt, boolean succeeded) {}
    public record PresenceView(String requirementId, String key, int revision) {}

    private static EnvironmentView toView(Environment e) {
        return new EnvironmentView(e.getId().toString(), e.getName(), e.getOrdinal());
    }

    private static DeploymentView toView(Deployment d) {
        return new DeploymentView(d.getId().toString(), d.getEnvironmentId().toString(), d.getBuildLabel(),
            d.getDeployedAt().toString(), d.isSucceeded());
    }

    /**
     * VYB-0374: what is actually in each environment.
     *
     * <p>{@code buildLabel} and {@code deployedAt} are null where nothing has ever been
     * deployed. The UI renders that as "not connected" rather than a zero (Principle 8),
     * so they are deliberately not defaulted here.
     */
    public record EnvironmentSummaryView(String id, String name, short ordinal, String buildLabel,
                                          String deployedAt, long requirementCount, boolean succeeded) {}

    @GetMapping("/environments/summary")
    public List<EnvironmentSummaryView> summaries() {
        return service.environmentSummaries().stream()
            .map(e -> new EnvironmentSummaryView(e.id(), e.name(), e.ordinal(), e.buildLabel(),
                e.deployedAt() == null ? null : e.deployedAt().toString(),
                e.requirementCount(), e.succeeded()))
            .toList();
    }

    public record RecentDeploymentView(String id, String environmentName, String buildLabel,
                                        String deployedAt, boolean succeeded, long requirementCount) {}

    @GetMapping("/deployments/recent")
    public List<RecentDeploymentView> recentDeployments(@RequestParam(defaultValue = "8") int limit) {
        return service.recent(Math.min(limit, 50)).stream()
            .map(d -> new RecentDeploymentView(d.id(), d.environmentName(), d.buildLabel(),
                d.deployedAt().toString(), d.succeeded(), d.requirementCount()))
            .toList();
    }

    public record ReleaseBlockerView(String requirementId, String key, String title, String reason) {}

    /** Principle 3 as a release gate: approved, but with no passing test at this revision. */
    @GetMapping("/deployments/blocked")
    public List<ReleaseBlockerView> blockedFromRelease(@RequestParam(defaultValue = "20") int limit) {
        return service.blockedFromRelease(Math.min(limit, 100)).stream()
            .map(b -> new ReleaseBlockerView(b.requirementId(), b.key(), b.title(), b.reason()))
            .toList();
    }

    /** VYB-0480 AC1: the order is whatever ordinal says, never alphabetical. */
    @GetMapping("/environments")
    public List<EnvironmentView> list() {
        return service.environments().stream().map(EnvironmentController::toView).toList();
    }

    @PostMapping("/environments")
    @ResponseStatus(HttpStatus.CREATED)
    public EnvironmentView create(@RequestBody CreateEnvironment body) {
        return toView(service.createEnvironment(body.name(), body.ordinal()));
    }

    @GetMapping("/environments/{id}/deployments")
    public List<DeploymentView> deploymentsFor(@PathVariable UUID id) {
        return deployments.findAllByEnvironmentIdOrderByDeployedAtDesc(id).stream()
            .map(EnvironmentController::toView).toList();
    }

    @GetMapping("/deployments/{id}/presence")
    public List<PresenceView> presence(@PathVariable UUID id) {
        return service.presence(id).stream()
            .map(p -> new PresenceView(p.requirementId(), p.key(), p.revision())).toList();
    }
}
