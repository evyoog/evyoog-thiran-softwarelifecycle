package com.vyoog.api.web;

import com.vyoog.identity.UserProvisioningService;
import com.vyoog.release.Release;
import com.vyoog.release.ReleaseService;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0474–0476/0483/0484. */
@RestController
@RequestMapping("/api/v1/releases")
public class ReleaseController {

    private final ReleaseService service;
    private final UserProvisioningService provisioning;

    public ReleaseController(ReleaseService service, UserProvisioningService provisioning) {
        this.service = service;
        this.provisioning = provisioning;
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    public record CreateRelease(@NotBlank String name) {}
    public record ReleaseView(String id, String name, String state, String targetDate) {}
    public record ScopeChange(@NotBlank String requirementId, @NotBlank String reason) {}
    public record SetTargetDate(@NotBlank String targetDate) {}

    private static ReleaseView toView(Release r) {
        return new ReleaseView(r.getId().toString(), r.getName(), r.getState().name(),
            r.getTargetDate() == null ? null : r.getTargetDate().toString());
    }

    @GetMapping
    public List<ReleaseView> list() {
        return service.list().stream().map(ReleaseController::toView).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReleaseView create(@RequestBody CreateRelease body) {
        return toView(service.create(body.name()));
    }

    @GetMapping("/{id}")
    public ReleaseView get(@PathVariable UUID id) {
        return service.list().stream().filter(r -> r.getId().equals(id)).findFirst()
            .map(ReleaseController::toView).orElseThrow(NoSuchElementException::new);
    }

    /** VYB-0372: the calendar's one real, settable planning date. */
    @PutMapping("/{id}/target-date")
    public ReleaseView setTargetDate(@PathVariable UUID id, @RequestBody SetTargetDate body,
                                      @AuthenticationPrincipal Jwt jwt) {
        return toView(service.setTargetDate(id, Instant.parse(body.targetDate()), currentUserId(jwt)));
    }

    @PostMapping("/{id}/scope")
    public void commit(@PathVariable UUID id, @RequestBody ScopeChange body, @AuthenticationPrincipal Jwt jwt) {
        service.commit(id, UUID.fromString(body.requirementId()), currentUserId(jwt), body.reason());
    }

    @DeleteMapping("/{id}/scope/{requirementId}")
    public void remove(@PathVariable UUID id, @PathVariable UUID requirementId,
                        @RequestParam String reason, @AuthenticationPrincipal Jwt jwt) {
        service.removeFromScope(id, requirementId, currentUserId(jwt), reason);
    }

    public record ScopeItemView(String requirementId) {}

    @GetMapping("/{id}/scope")
    public List<String> scope(@PathVariable UUID id) {
        return service.scope(id).stream().map(UUID::toString).toList();
    }

    public record MovementView(
        String requirementId, String direction, String reason, String movedBy, String movedAt) {}

    /** VYB-0516 AC1: the window is selectable — defaults to the trailing 90 days. */
    @GetMapping("/{id}/movements")
    public List<MovementView> movements(@PathVariable UUID id,
                                          @RequestParam(required = false) Instant from,
                                          @RequestParam(required = false) Instant to) {
        Instant effectiveFrom = from != null ? from : Instant.now().minusSeconds(90L * 86_400);
        Instant effectiveTo = to != null ? to : Instant.now();
        return service.movements(id, effectiveFrom, effectiveTo).stream()
            .map(m -> new MovementView(m.getRequirementId().toString(), m.getDirection().name(), m.getReason(),
                m.getMovedBy() == null ? null : m.getMovedBy().toString(), m.getMovedAt().toString()))
            .toList();
    }

    public record ReadinessView(int committed, int verified, double verifiedRatio, int criticalOpenGaps) {}

    @GetMapping("/{id}/readiness")
    public ReadinessView readiness(@PathVariable UUID id) {
        var r = service.readiness(id);
        return new ReadinessView(r.committed(), r.verified(), r.verifiedRatio(), r.criticalOpenGaps());
    }

    public record BlockedView(String requirementId, String key, String reason) {}

    @GetMapping("/{id}/blocked")
    public List<BlockedView> blocked(@PathVariable UUID id) {
        return service.blocked(id).stream()
            .map(b -> new BlockedView(b.requirementId(), b.key(), b.reason())).toList();
    }

    public record NoteItemView(String requirementId, String key, String title, String capabilityName) {}
    public record ReleaseNotesView(java.util.Map<String, List<NoteItemView>> approvedByCapability, List<NoteItemView> held) {}

    /** VYB-0484 AC1/VYB-0810: held (not-yet-Approved committed) items are on this response too, never omitted. */
    @GetMapping("/{id}/notes")
    public ReleaseNotesView notes(@PathVariable UUID id) {
        var n = service.releaseNotes(id);
        java.util.Map<String, List<NoteItemView>> byCapability = new java.util.LinkedHashMap<>();
        n.approvedByCapability().forEach((cap, items) -> byCapability.put(cap,
            items.stream().map(i -> new NoteItemView(i.requirementId(), i.key(), i.title(), i.capabilityName())).toList()));
        List<NoteItemView> held = n.held().stream()
            .map(i -> new NoteItemView(i.requirementId(), i.key(), i.title(), i.capabilityName())).toList();
        return new ReleaseNotesView(byCapability, held);
    }
}
