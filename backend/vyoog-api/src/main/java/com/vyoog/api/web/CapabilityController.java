package com.vyoog.api.web;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.portfolio.ApplicationRepository;
import com.vyoog.portfolio.Capability;
import com.vyoog.portfolio.CapabilityRepository;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0100: every capability belongs to exactly one application. */
@RestController
@RequestMapping("/api/v1/applications/{applicationId}/capabilities")
public class CapabilityController {

    private final CapabilityRepository capabilities;
    private final ApplicationRepository applications;
    private final PrincipalGuard guard;

    public CapabilityController(CapabilityRepository capabilities, ApplicationRepository applications, PrincipalGuard guard) {
        this.capabilities = capabilities;
        this.applications = applications;
        this.guard = guard;
    }

    public record CapabilityView(String id, String applicationId, String name, String code, String description, boolean archived) {}
    public record CreateCapability(@NotBlank String name, String code, String description) {}
    public record UpdateCapability(@NotBlank String name, String code, String description) {}

    private static CapabilityView toView(Capability c) {
        return new CapabilityView(
            c.getId().toString(), c.getApplicationId().toString(), c.getName(), c.getCode(), c.getDescription(), c.isArchived());
    }

    @GetMapping
    public List<CapabilityView> list(@PathVariable UUID applicationId) {
        return capabilities.findAllByApplicationIdAndArchivedAtIsNull(applicationId).stream()
            .map(CapabilityController::toView).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CapabilityView create(@PathVariable UUID applicationId, @RequestBody CreateCapability body) {
        // AC1: creating a capability without a valid, unarchived application is rejected.
        applications.findById(applicationId)
            .filter(a -> !a.isArchived())
            .orElseThrow(() -> new IllegalArgumentException("No such application: " + applicationId));
        Capability c = new Capability(applicationId, body.name());
        c.setCode(body.code());
        c.setDescription(body.description());
        capabilities.save(c);
        return toView(c);
    }

    @PatchMapping("/{id}")
    public CapabilityView update(@PathVariable UUID applicationId, @PathVariable UUID id,
                                  @RequestBody UpdateCapability body) {
        Capability c = capabilities.findById(id).orElseThrow(NoSuchElementException::new);
        c.setName(body.name());
        c.setCode(body.code());
        c.setDescription(body.description());
        capabilities.save(c);
        return toView(c);
    }

    /** VYB-0790: archiving hides this capability from placement everywhere it's picked. */
    @PostMapping("/{id}/archive")
    public CapabilityView archive(@PathVariable UUID applicationId, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        guard.requireAdministrator(jwt);
        Capability c = capabilities.findById(id).orElseThrow(NoSuchElementException::new);
        c.archive();
        capabilities.save(c);
        return toView(c);
    }
}
