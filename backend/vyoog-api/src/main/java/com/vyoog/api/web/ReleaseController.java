package com.vyoog.api.web;

import com.vyoog.identity.AccessRule;

import com.vyoog.api.config.RequiresAccess;

import com.vyoog.identity.UserProvisioningService;
import com.vyoog.release.Release;
import com.vyoog.release.ReleaseNotesExporter;
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
    private final ReleaseNotesExporter exporter;
    private final UserProvisioningService provisioning;

    public ReleaseController(ReleaseService service, ReleaseNotesExporter exporter, UserProvisioningService provisioning) {
        this.service = service;
        this.exporter = exporter;
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

    // VYB-0906: releases are an Approver / Product Owner decision (matrix: Baseline).
    @RequiresAccess(value = AccessRule.BASELINE, scope = RequiresAccess.Scope.ANYWHERE)
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
    @RequiresAccess(value = AccessRule.BASELINE, scope = RequiresAccess.Scope.ANYWHERE)
    @PutMapping("/{id}/target-date")
    public ReleaseView setTargetDate(@PathVariable UUID id, @RequestBody SetTargetDate body,
                                      @AuthenticationPrincipal Jwt jwt) {
        return toView(service.setTargetDate(id, Instant.parse(body.targetDate()), currentUserId(jwt)));
    }

    @RequiresAccess(value = AccessRule.BASELINE, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping("/{id}/scope")
    public void commit(@PathVariable UUID id, @RequestBody ScopeChange body, @AuthenticationPrincipal Jwt jwt) {
        service.commit(id, UUID.fromString(body.requirementId()), currentUserId(jwt), body.reason());
    }

    @RequiresAccess(value = AccessRule.BASELINE, scope = RequiresAccess.Scope.ANYWHERE)
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

    // ------------------------------------------------------------ VYB-0930

    public record ScopeItemDetailView(String requirementId, String key, String title, String status, String capabilityName) {}

    /** What is committed, with key and title, so a screen shows requirements rather than ids. */
    @GetMapping("/{id}/scope/items")
    public List<ScopeItemDetailView> scopeItems(@PathVariable UUID id) {
        return service.scopeItems(id).stream()
            .map(i -> new ScopeItemDetailView(i.requirementId(), i.key(), i.title(), i.status(), i.capabilityName())).toList();
    }

    public record ReleaseCandidateView(String requirementId, String key, String title, String status, String committedToId,
                                 String committedToName) {}

    /** The requirement picker's source: searchable by key or title, each saying which release (if any) already holds it. */
    @GetMapping("/{id}/candidates")
    public org.springframework.data.domain.Page<ReleaseCandidateView> candidates(
            @PathVariable UUID id, @RequestParam(required = false) String q,
            @org.springframework.data.web.PageableDefault(size = 20) org.springframework.data.domain.Pageable pageable) {
        return service.candidates(id, q, pageable).map(c -> new ReleaseCandidateView(c.requirementId(), c.key(), c.title(), c.status(),
            c.committedToId(), c.committedToName()));
    }

    public record BulkScopeChange(@jakarta.validation.constraints.NotEmpty List<String> requirementIds, @NotBlank String reason) {}

    public record BulkScopeResult(List<String> committed, List<String> alreadyCommitted) {}

    /** Commits several requirements with one reason, all or nothing. A single refusal (locked scope, taken elsewhere) commits none. */
    @RequiresAccess(value = AccessRule.BASELINE, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping("/{id}/scope/bulk")
    public BulkScopeResult commitMany(@PathVariable UUID id, @RequestBody @jakarta.validation.Valid BulkScopeChange body,
                                       @AuthenticationPrincipal Jwt jwt) {
        var r = service.commitAll(id, body.requirementIds().stream().map(UUID::fromString).toList(), currentUserId(jwt), body.reason());
        return new BulkScopeResult(r.committed().stream().map(UUID::toString).toList(),
            r.alreadyCommitted().stream().map(UUID::toString).toList());
    }

    /** The release notes as a file: {@code format=markdown} or {@code format=docx}. Held requirements are in it, listed separately. */
    @GetMapping("/{id}/notes/export")
    public org.springframework.http.ResponseEntity<byte[]> exportNotes(@PathVariable UUID id, @RequestParam String format) {
        Release release = service.find(id);
        var header = new ReleaseNotesExporter.Header(release.getName(), release.getState(), release.getTargetDate(), Instant.now());
        var notes = service.releaseNotes(id);
        byte[] bytes;
        String contentType, extension;
        switch (format.toLowerCase(java.util.Locale.ROOT)) {
            case "markdown", "md" -> {
                bytes = exporter.markdown(header, notes).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                contentType = ReleaseNotesExporter.MARKDOWN_CONTENT_TYPE;
                extension = "md";
            }
            case "docx", "word" -> {
                bytes = exporter.docx(header, notes);
                contentType = ReleaseNotesExporter.DOCX_CONTENT_TYPE;
                extension = "docx";
            }
            default -> throw new IllegalArgumentException("Unknown format '" + format + "': use markdown or docx");
        }
        String slug = release.getName().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        String filename = (slug.isEmpty() ? "release" : slug) + "-release-notes." + extension;
        return org.springframework.http.ResponseEntity.ok()
            .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, contentType)
            .header("X-Content-Type-Options", "nosniff")
            .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                org.springframework.http.ContentDisposition.attachment().filename(filename).build().toString())
            .body(bytes);
    }
}
