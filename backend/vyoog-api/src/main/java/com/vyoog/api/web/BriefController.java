package com.vyoog.api.web;

import com.vyoog.identity.AccessRule;

import com.vyoog.api.config.RequiresAccess;

import com.vyoog.ai.AiProviderUnavailableException;
import com.vyoog.brief.Brief;
import com.vyoog.brief.BriefPushService;
import com.vyoog.brief.BriefElaborationDrafter;
import com.vyoog.brief.BriefService;
import com.vyoog.brief.BriefStalenessService;
import com.vyoog.brief.BriefSection;
import com.vyoog.brief.BriefTarget;
import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.ScopeType;
import com.vyoog.identity.UserProvisioningService;
import com.vyoog.portfolio.Application;
import com.vyoog.portfolio.ApplicationRepository;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0450–0457. */
@RestController
@RequestMapping("/api/v1/briefs")
public class BriefController {

    private final BriefService service;
    private final BriefStalenessService staleness;
    private final BriefPushService push;
    private final ApplicationRepository applications;
    private final UserProvisioningService provisioning;
    private final PrincipalGuard guard;
    private final BriefElaborationDrafter drafter;

    public BriefController(BriefService service, BriefStalenessService staleness, BriefPushService push,
                            ApplicationRepository applications, UserProvisioningService provisioning,
                            PrincipalGuard guard, BriefElaborationDrafter drafter) {
        this.service = service;
        this.staleness = staleness;
        this.push = push;
        this.applications = applications;
        this.provisioning = provisioning;
        this.guard = guard;
        this.drafter = drafter;
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    /**
     * {@code sections} null or empty means every section, so an older client is
     * unaffected. {@code includeReviewedElaborations} (VYB-0938, was VYB-0817's
     * {@code includeAiElaboration}) is a separate, explicit opt-in: null/absent means false.
     * Generating never calls the AI; it adds the elaborations a person accepted at the
     * review endpoint, for each requirement's current revision.
     */
    public record GenerateBrief(
        @NotBlank String applicationId, List<String> capabilityIds, @NotBlank String target,
        @NotBlank String developerId, Set<BriefSection> sections, Boolean includeReviewedElaborations) {}
    public record BriefView(
        String id, String applicationId, String applicationName, String target, String developerId, String content,
        String generatedAt, boolean stale, List<String> staleBecause) {}

    private BriefView toView(Brief b, String applicationName) {
        List<String> movedKeys = b.isStale() ? staleness.movedRequirementKeys(b.getId()) : List.of();
        return new BriefView(b.getId().toString(), b.getApplicationId().toString(), applicationName, b.getTarget().name(),
            b.getDeveloperId().toString(), b.getContent(), b.getGeneratedAt().toString(), b.isStale(), movedKeys);
    }

    // VYB-0906: generating a brief stores a document built from requirements; pushing it stays Approver-only.
    @RequiresAccess(value = AccessRule.CREATE_EDIT_REQ, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BriefView generate(@RequestBody GenerateBrief body, @AuthenticationPrincipal Jwt jwt) {
        UUID applicationId = UUID.fromString(body.applicationId());
        // Named, not bare: a brief that fails because the application is gone and one
        // that fails because the developer is gone produced the same opaque 404 before.
        String applicationName = applications.findById(applicationId)
            .orElseThrow(() -> new NoSuchElementException("No such application")).getName();
        List<UUID> capabilityIds = body.capabilityIds() == null ? List.of()
            : body.capabilityIds().stream().map(UUID::fromString).toList();
        try {
            Brief brief = service.generate(applicationId, applicationName, capabilityIds,
                BriefTarget.valueOf(body.target()), UUID.fromString(body.developerId()), currentUserId(jwt),
                body.sections(), Boolean.TRUE.equals(body.includeReviewedElaborations()));
            return toView(brief, applicationName);
        } catch (AiProviderUnavailableException e) {
            throw new IllegalStateException(e.getMessage());
        }
    }

    public record ElaborationScope(@NotBlank String applicationId, List<String> capabilityIds) {}
    public record DraftedView(int requirementsInScope, int proposals, List<String> proposalIds) {}

    /**
     * VYB-0938: asks the AI to elaborate the requirements a brief for this scope would carry, and records each answer as a
     * pending proposal. Nothing is applied and no brief is written; a person decides each proposal at
     * {@code POST /ai-proposals/{id}/decision}. Same rule as generating a brief.
     */
    @RequiresAccess(value = AccessRule.CREATE_EDIT_REQ, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping("/elaborations")
    @ResponseStatus(HttpStatus.CREATED)
    public DraftedView draftElaborations(@RequestBody ElaborationScope body, @AuthenticationPrincipal Jwt jwt) {
        UUID applicationId = UUID.fromString(body.applicationId());
        String applicationName = applications.findById(applicationId)
            .orElseThrow(() -> new NoSuchElementException("No such application")).getName();
        try {
            var drafted = drafter.draft(applicationId, applicationName, capabilityIdsOf(body.capabilityIds()), currentUserId(jwt));
            return new DraftedView(drafted.requirementsInScope(), drafted.proposals(),
                drafted.proposalIds().stream().map(UUID::toString).toList());
        } catch (AiProviderUnavailableException e) {
            throw new IllegalStateException(e.getMessage());
        }
    }

    public record ElaborationStatusView(int requirementsInScope, int accepted, List<AiProposalController.ProposalView> pending) {}

    /** VYB-0938: for a scope, how many requirements have a reviewed elaboration at their current revision, and what is waiting for review. */
    @GetMapping("/elaborations")
    public ElaborationStatusView elaborationStatus(@RequestParam UUID applicationId, @RequestParam(required = false) List<UUID> capabilityIds) {
        var status = drafter.status(applicationId, capabilityIds == null ? List.of() : capabilityIds);
        return new ElaborationStatusView(status.requirementsInScope(), status.accepted(),
            status.pending().stream().map(AiProposalController::toView).toList());
    }

    private static List<UUID> capabilityIdsOf(List<String> raw) {
        return raw == null ? List.of() : raw.stream().map(UUID::fromString).toList();
    }

    /**
     * VYB-0836: {@code applicationId} is now optional — omitting it is how the Delivery
     * screen's history shows something even before a product/application is picked,
     * rather than staying empty until someone scopes into one. One batched application
     * lookup for the whole page, not one per row.
     */
    @GetMapping
    public List<BriefView> history(@RequestParam(required = false) UUID applicationId) {
        List<Brief> found = service.history(applicationId);
        Map<UUID, String> names = applications.findAllById(
            found.stream().map(Brief::getApplicationId).distinct().toList()
        ).stream().collect(Collectors.toMap(Application::getId, Application::getName));
        return found.stream()
            .map(b -> toView(b, names.getOrDefault(b.getApplicationId(), "Unknown application")))
            .toList();
    }

    @GetMapping("/{id}")
    public BriefView get(@PathVariable UUID id) {
        Brief b = service.get(id);
        String applicationName = applications.findById(b.getApplicationId())
            .map(Application::getName).orElse("Unknown application");
        return toView(b, applicationName);
    }

    public record PushResultView(boolean success, int statusCode, String error) {}

    /**
     * VYB-0818: pushes the generated brief to whatever the "planning" connection is
     * configured with — refuses with a named reason, same treatment as the
     * AI-elaboration refusal above, when nothing is configured yet.
     */
    @PostMapping("/{id}/push")
    public PushResultView push(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        // VYB-0902 (F02): this sends requirement content to an external system, an act nobody can
        // take back. The matrix's nearest column is "Baseline" (Approver / Product Owner), so:
        // APPROVER on the brief's application (a grant on its product counts), or ADMINISTRATOR.
        guard.requireAnyRoleOrAdmin(jwt, java.util.List.of(AccessRole.APPROVER), ScopeType.APP,
            service.get(id).getApplicationId(), "push a brief to the planning tool");
        BriefPushService.PushResult result = push.push(id, currentUserId(jwt));
        return new PushResultView(result.success(), result.statusCode(), result.error());
    }
}
