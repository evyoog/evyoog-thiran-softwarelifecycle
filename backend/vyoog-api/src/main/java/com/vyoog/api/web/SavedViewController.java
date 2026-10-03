package com.vyoog.api.web;

import com.vyoog.identity.AccessRule;

import com.vyoog.api.config.RequiresAccess;

import com.vyoog.identity.UserProvisioningService;
import com.vyoog.savedview.SavedView;
import com.vyoog.savedview.SavedViewService;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** A person's own saved requirement-grid filters. Every route is scoped to the caller. */
@RestController
@RequestMapping("/api/v1/saved-views")
public class SavedViewController {

    private final SavedViewService service;
    private final UserProvisioningService provisioning;

    public SavedViewController(SavedViewService service, UserProvisioningService provisioning) {
        this.service = service;
        this.provisioning = provisioning;
    }

    public record SavedViewView(String id, String name, String status, String priority, String type,
                                 String titleContains, String capabilityId) {}

    private static SavedViewView toView(SavedView v) {
        return new SavedViewView(v.getId().toString(), v.getName(), v.getStatus(), v.getPriority(),
            v.getType(), v.getTitleContains(),
            v.getCapabilityId() == null ? null : v.getCapabilityId().toString());
    }

    @GetMapping
    public List<SavedViewView> mine(@AuthenticationPrincipal Jwt jwt) {
        return service.mine(currentUserId(jwt)).stream().map(SavedViewController::toView).toList();
    }

    public record SaveView(@NotBlank String name, String status, String priority, String type,
                            String titleContains, String capabilityId) {}

    // VYB-0906: the caller's own saved view.
    @RequiresAccess(value = AccessRule.PERSON)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SavedViewView save(@RequestBody SaveView body, @AuthenticationPrincipal Jwt jwt) {
        return toView(service.save(currentUserId(jwt), body.name(), body.status(), body.priority(),
            body.type(), body.titleContains(),
            body.capabilityId() == null || body.capabilityId().isBlank()
                ? null : UUID.fromString(body.capabilityId())));
    }

    // VYB-0906: the service only deletes the caller's own view.
    @RequiresAccess(value = AccessRule.PERSON)
    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        service.delete(id, currentUserId(jwt));
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }
}
