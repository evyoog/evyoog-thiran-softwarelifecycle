package com.vyoog.api.web;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.baseline.Baseline;
import com.vyoog.baseline.BaselineService;
import com.vyoog.identity.UserProvisioningService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0470/0472. */
@RestController
@RequestMapping("/api/v1/baselines")
public class BaselineController {

    private final BaselineService service;
    private final UserProvisioningService provisioning;
    private final PrincipalGuard guard;

    public BaselineController(BaselineService service, UserProvisioningService provisioning, PrincipalGuard guard) {
        this.service = service;
        this.provisioning = provisioning;
        this.guard = guard;
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    public record FreezeRequest(@NotBlank String name, String releaseId, @NotEmpty List<String> requirementIds) {}
    public record BaselineView(
        String id, String name, String releaseId, String frozenAt, String frozenBy, int gapsAtFreeze) {}

    private static BaselineView toView(Baseline b) {
        return new BaselineView(b.getId().toString(), b.getName(),
            b.getReleaseId() == null ? null : b.getReleaseId().toString(),
            b.getFrozenAt().toString(), b.getFrozenBy() == null ? null : b.getFrozenBy().toString(), b.getGapsAtFreeze());
    }

    /** VYB-0471: step-up is checked here, before freeze ever runs — the domain layer never sees the JWT. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BaselineView freeze(@RequestBody FreezeRequest body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireHuman(jwt);
        guard.requireStepUp(jwt);
        Baseline b = service.freeze(body.name(), body.releaseId() == null ? null : UUID.fromString(body.releaseId()),
            body.requirementIds().stream().map(UUID::fromString).toList(), currentUserId(jwt));
        return toView(b);
    }

    @GetMapping
    public List<BaselineView> list() {
        return service.list().stream().map(BaselineController::toView).toList();
    }

    public record ItemView(String requirementId, String key, int revision) {}

    @GetMapping("/{id}/items")
    public List<ItemView> items(@PathVariable UUID id) {
        return service.items(id).stream()
            .map(i -> new ItemView(i.requirementId(), i.key(), i.revision())).toList();
    }

    public record ChangedView(String requirementId, String key, int fromRevision, int toRevision) {}
    public record DiffView(List<ItemView> added, List<ItemView> removed, List<ChangedView> changed) {}

    @GetMapping("/diff")
    public DiffView diff(@RequestParam UUID from, @RequestParam UUID to) {
        var d = service.diff(from, to);
        return new DiffView(
            d.added().stream().map(i -> new ItemView(i.requirementId(), i.key(), i.revision())).toList(),
            d.removed().stream().map(i -> new ItemView(i.requirementId(), i.key(), i.revision())).toList(),
            d.changed().stream().map(c -> new ChangedView(c.requirementId(), c.key(), c.fromRevision(), c.toRevision())).toList());
    }
}
