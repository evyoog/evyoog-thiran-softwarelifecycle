package com.vyoog.api.web;

import com.vyoog.identity.AccessRule;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.api.config.RequiresAccess;

import com.vyoog.defect.Defect;
import com.vyoog.defect.DefectLifecycleService;
import com.vyoog.defect.DefectRepository;
import com.vyoog.defect.DefectSeverity;
import com.vyoog.defect.DefectService;
import com.vyoog.defect.DefectState;
import com.vyoog.defect.FoundIn;
import com.vyoog.defect.RootCause;
import com.vyoog.identity.UserProvisioningService;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0320–0323/0364/0365. */
@RestController
@RequestMapping("/api/v1")
public class DefectController {

    private final DefectRepository defects;
    private final DefectService service;
    private final DefectLifecycleService lifecycle;
    private final UserProvisioningService provisioning;
    private final PrincipalGuard guard;

    public DefectController(DefectRepository defects, DefectService service, DefectLifecycleService lifecycle,
                             UserProvisioningService provisioning, PrincipalGuard guard) {
        this.defects = defects;
        this.service = service;
        this.lifecycle = lifecycle;
        this.provisioning = provisioning;
        this.guard = guard;
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    public record DefectView(
        String id, String key, String title, String severity, String requirementId, boolean untraced,
        String foundIn, String rootCause, String developerId, String testerId, String state, String raisedAt,
        String requirementKey, String developerName, String testerName, String releaseId, String releaseName) {}

    public record RaiseDefect(
        @NotBlank String title, @NotBlank String severity, String requirementId, @NotBlank String foundIn) {}
    public record ClassifyDefect(@NotBlank String rootCause) {}
    public record RootCauseSplitView(String rootCause, long count) {}

    private static DefectView toView(Defect d) {
        return toView(d, null, null, null, null, null);
    }

    private static DefectView toView(Defect d, String requirementKey, String developerName, String testerName, UUID releaseId,
                                      String releaseName) {
        return new DefectView(
            d.getId().toString(), d.getKey(), d.getTitle(), d.getSeverity().name(),
            d.getRequirementId() == null ? null : d.getRequirementId().toString(), d.isUntraced(),
            d.getFoundIn().name(), d.getRootCause() == null ? null : d.getRootCause().name(),
            d.getDeveloperId() == null ? null : d.getDeveloperId().toString(),
            d.getTesterId() == null ? null : d.getTesterId().toString(),
            d.getState().name(), d.getRaisedAt().toString(), requirementKey, developerName, testerName,
            releaseId == null ? null : releaseId.toString(), releaseName);
    }

    /**
     * The defect list. {@code state} is OPEN (the default), FIXED, CLOSED or ALL; the rest narrow it by severity, release,
     * assignee (developer or tester) and a key-or-title search. Most severe first, then newest.
     */
    @GetMapping("/defects")
    public Page<DefectView> list(@RequestParam(defaultValue = "OPEN") String state,
                                  @RequestParam(required = false) DefectSeverity severity,
                                  @RequestParam(required = false) UUID releaseId,
                                  @RequestParam(required = false) UUID assignedTo,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "50") int size) {
        if (!state.equalsIgnoreCase("ALL")) {
            try {
                DefectState.valueOf(state.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown state '" + state + "': use OPEN, FIXED, CLOSED or ALL");
            }
        }
        return lifecycle.list(new DefectLifecycleService.Filter(state, severity, releaseId, assignedTo, q), PageRequest.of(page, size))
            .map(r -> toView(r.defect(), r.requirementKey(), r.developerName(), r.testerName(), r.releaseId(), r.releaseName()));
    }

    /** VYB-0365: raising from the requirement detail panel means requirementId always arrives set. */
    // VYB-0906: defects are QA work (matrix: Verify).
    @RequiresAccess(value = AccessRule.VERIFY, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping("/defects")
    @ResponseStatus(HttpStatus.CREATED)
    public DefectView raise(@RequestBody RaiseDefect body, @AuthenticationPrincipal Jwt jwt) {
        Defect d = service.raise(body.title(), DefectSeverity.valueOf(body.severity()),
            body.requirementId() == null ? null : UUID.fromString(body.requirementId()),
            FoundIn.valueOf(body.foundIn()), currentUserId(jwt));
        return toView(d);
    }

    @RequiresAccess(value = AccessRule.VERIFY, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping("/defects/{id}/classify")
    public DefectView classify(@PathVariable UUID id, @RequestBody ClassifyDefect body,
                                @AuthenticationPrincipal Jwt jwt) {
        return toView(service.classify(id, RootCause.valueOf(body.rootCause()), currentUserId(jwt)));
    }

    @RequiresAccess(value = AccessRule.VERIFY, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping("/defects/{id}/close")
    public DefectView close(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return toView(service.close(id, currentUserId(jwt)));
    }

    /** VYB-0323/0364 AC2: the requirement-versus-coding split, filterable by capability. */
    @GetMapping("/defects/root-cause-split")
    public List<RootCauseSplitView> rootCauseSplit(@RequestParam(required = false) UUID capabilityId) {
        return service.rootCauseSplit(capabilityId).stream()
            .map(s -> new RootCauseSplitView(s.rootCause(), s.count()))
            .toList();
    }

    // ------------------------------------------------------------ VYB-0931

    public record RefView(String id, String label) {}

    public record TransitionView(String id, String from, String to, String reason, String changedBy, String changedByName, String changedAt) {}

    public record DefectDetailView(
        DefectView defect, String requirementTitle, RefView testCase, RefView testRun, RefView release, String raisedFromRunId,
        String raisedFromRunStepId, String raisedFromRunCaseId, String raisedFromTestKey, List<TransitionView> transitions) {}

    public record CommentView(String id, String authorId, String authorName, String body, String createdAt) {}

    public record MarkFixed(String note) {}
    public record Reopen(@NotBlank String reason) {}
    public record EditDefect(@NotBlank String title, @NotBlank String severity, @NotBlank String foundIn) {}
    public record Assignment(UUID developerId, UUID testerId) {}
    public record DefectLinks(UUID testCaseId, UUID testRunId, UUID releaseId) {}
    public record AddComment(@NotBlank String body) {}

    private static RefView ref(DefectLifecycleService.Ref r) {
        return r == null ? null : new RefView(r.id().toString(), r.label());
    }

    private static CommentView view(DefectLifecycleService.Comment c) {
        return new CommentView(c.id().toString(), c.authorId() == null ? null : c.authorId().toString(), c.authorName(), c.body(), c.createdAt().toString());
    }

    @GetMapping("/defects/{id}")
    public DefectDetailView detail(@PathVariable UUID id) {
        var d = lifecycle.detail(id);
        var defect = toView(d.defect(), d.requirementKey(), d.developerName(), d.testerName(),
            d.release() == null ? null : d.release().id(), d.release() == null ? null : d.release().label());
        return new DefectDetailView(defect, d.requirementTitle(), ref(d.testCase()), ref(d.testRun()), ref(d.release()),
            d.raisedFromRunId() == null ? null : d.raisedFromRunId().toString(),
            d.raisedFromRunStepId() == null ? null : d.raisedFromRunStepId().toString(),
            d.raisedFromRunCaseId() == null ? null : d.raisedFromRunCaseId().toString(), d.raisedFromTestKey(),
            d.transitions().stream().map(t -> new TransitionView(t.id().toString(), t.from().name(), t.to().name(), t.reason(),
                t.changedBy() == null ? null : t.changedBy().toString(), t.changedByName(), t.changedAt().toString())).toList());
    }

    /**
     * OPEN to FIXED. The defect's assigned developer may do it; so may a Tester or an administrator (the matrix has no
     * Developer column, so this is checked here, against the defect, rather than by a role alone).
     */
    @PostMapping("/defects/{id}/fix")
    public DefectView markFixed(@PathVariable UUID id, @RequestBody(required = false) MarkFixed body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireHuman(jwt);
        UUID me = currentUserId(jwt);
        var existing = defects.findById(id).orElseThrow(java.util.NoSuchElementException::new);
        if (!me.equals(existing.getDeveloperId())) {
            guard.requireRuleAnywhere(jwt, AccessRule.VERIFY, "mark a defect fixed unless it is assigned to you");
        }
        return toView(lifecycle.markFixed(id, body == null ? null : body.note(), me));
    }

    @RequiresAccess(value = AccessRule.VERIFY, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping("/defects/{id}/reopen")
    public DefectView reopen(@PathVariable UUID id, @RequestBody Reopen body, @AuthenticationPrincipal Jwt jwt) {
        return toView(lifecycle.reopen(id, body.reason(), currentUserId(jwt)));
    }

    @RequiresAccess(value = AccessRule.VERIFY, scope = RequiresAccess.Scope.ANYWHERE)
    @PutMapping("/defects/{id}")
    public DefectView edit(@PathVariable UUID id, @RequestBody EditDefect body, @AuthenticationPrincipal Jwt jwt) {
        return toView(lifecycle.edit(id, body.title(), DefectSeverity.valueOf(body.severity()), FoundIn.valueOf(body.foundIn()), currentUserId(jwt)));
    }

    /** Replaces who the defect is routed to (null means nobody). */
    @RequiresAccess(value = AccessRule.VERIFY, scope = RequiresAccess.Scope.ANYWHERE)
    @PutMapping("/defects/{id}/assignment")
    public DefectView assign(@PathVariable UUID id, @RequestBody Assignment body, @AuthenticationPrincipal Jwt jwt) {
        return toView(lifecycle.assign(id, body.developerId(), body.testerId(), currentUserId(jwt)));
    }

    /** Replaces the defect's links to a test case, a test run and a release (null clears one). */
    @RequiresAccess(value = AccessRule.VERIFY, scope = RequiresAccess.Scope.ANYWHERE)
    @PutMapping("/defects/{id}/links")
    public DefectDetailView link(@PathVariable UUID id, @RequestBody DefectLinks body, @AuthenticationPrincipal Jwt jwt) {
        lifecycle.link(id, body.testCaseId(), body.testRunId(), body.releaseId(), currentUserId(jwt));
        return detail(id);
    }

    @GetMapping("/defects/{id}/comments")
    public List<CommentView> comments(@PathVariable UUID id) {
        return lifecycle.comments(id).stream().map(DefectController::view).toList();
    }

    /** Any signed-in person may comment. Comments are append-only: no edit, no delete. */
    @RequiresAccess(value = AccessRule.PERSON, scope = RequiresAccess.Scope.NONE)
    @PostMapping("/defects/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentView addComment(@PathVariable UUID id, @RequestBody AddComment body, @AuthenticationPrincipal Jwt jwt) {
        return view(lifecycle.comment(id, body.body(), currentUserId(jwt)));
    }
}
