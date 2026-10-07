package com.vyoog.api.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.vyoog.api.config.AccessScopeResolver;
import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.api.config.RequiresAccess;
import com.vyoog.identity.AccessRule;
import com.vyoog.identity.UserProvisioningService;
import com.vyoog.proposal.AiProposalService;
import com.vyoog.proposal.ProposalKind;
import com.vyoog.proposal.ProposalState;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * VYB-0938 (F30): the one review endpoint for AI proposals. A proposal is recorded when the AI makes it; this is where a
 * person accepts it (optionally edited), or rejects it. Accepting applies the change through the ordinary service, so a
 * hand-made change's rules all apply. Reads are open to any signed-in person.
 *
 * <p>Who may decide is the rule that would let them make the same change by hand ({@link ProposalKind#rule()}): checked at
 * the requirement's scope when the proposal has one, and anywhere otherwise. A service account may not.
 */
@RestController
@RequestMapping("/api/v1/ai-proposals")
public class AiProposalController {

    private final AiProposalService proposals;
    private final AccessScopeResolver scopes;
    private final PrincipalGuard guard;
    private final UserProvisioningService provisioning;

    public AiProposalController(AiProposalService proposals, AccessScopeResolver scopes, PrincipalGuard guard,
                                UserProvisioningService provisioning) {
        this.proposals = proposals;
        this.scopes = scopes;
        this.guard = guard;
        this.provisioning = provisioning;
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    public record ProposalView(String id, String kind, String state, String requirementId, String requirementKey,
                               String requirementTitle, Integer requirementRevision, Integer currentRevision, boolean stale,
                               JsonNode payload, JsonNode acceptedPayload, String model, String proposedByName,
                               String proposedAt, String decidedByName, String decidedAt, String decisionReason,
                               String appliedType, String appliedId) {}

    static ProposalView toView(AiProposalService.Proposal p) {
        return new ProposalView(p.id().toString(), p.kind().name(), p.state().name(),
            p.requirementId() == null ? null : p.requirementId().toString(), p.requirementKey(), p.requirementTitle(),
            p.requirementRevision(), p.currentRevision(), p.stale(), p.payload(), p.acceptedPayload(), p.model(),
            p.proposedByName(), p.proposedAt() == null ? null : p.proposedAt().toString(), p.decidedByName(),
            p.decidedAt() == null ? null : p.decidedAt().toString(), p.decisionReason(), p.appliedType(),
            p.appliedId() == null ? null : p.appliedId().toString());
    }

    /** {@code state} defaults to PENDING; {@code ALL} lists every state. An unknown state or kind is a 400. */
    @RequiresAccess(AccessRule.PERSON)
    @GetMapping
    public Page<ProposalView> list(@RequestParam(defaultValue = "PENDING") String state, @RequestParam(required = false) String kind,
                                   @RequestParam(required = false) UUID requirementId,
                                   @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        ProposalState wantedState = state.equalsIgnoreCase("ALL") ? null : parse(ProposalState.class, state, "state");
        ProposalKind wantedKind = kind == null || kind.isBlank() ? null : parse(ProposalKind.class, kind, "kind");
        return proposals.list(new AiProposalService.Filter(wantedState, wantedKind, requirementId),
            PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200))).map(AiProposalController::toView);
    }

    @RequiresAccess(AccessRule.PERSON)
    @GetMapping("/{id}")
    public ProposalView get(@PathVariable UUID id) {
        return toView(proposals.get(id));
    }

    /**
     * @param decision {@code ACCEPT} or {@code REJECT}
     * @param edits    on ACCEPT only: the payload fields changed first (rewrite: {@code statement}; test case:
     *                 {@code title}, {@code description}; elaboration: {@code detail}). The original is kept beside them.
     * @param reason   why, for a rejection; optional
     */
    public record Decide(@NotBlank String decision, Map<String, String> edits, String reason) {}

    @PostMapping("/{id}/decision")
    public ProposalView decide(@PathVariable UUID id, @RequestBody Decide body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireHuman(jwt);
        AiProposalService.Proposal p = proposals.get(id);
        if (p.requirementId() != null) {
            var s = scopes.ofRequirement(p.requirementId());
            guard.requireAnyRoleOrAdmin(jwt, p.kind().rule().roles(), s.type(), s.id(), "decide this " + p.kind() + " proposal");
        } else {
            guard.requireRuleAnywhere(jwt, p.kind().rule(), "decide this " + p.kind() + " proposal");
        }
        AiProposalService.Decision decision = parse(AiProposalService.Decision.class, body.decision(), "decision");
        return toView(proposals.decide(id, decision, body.edits(), body.reason(), currentUserId(jwt)));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw, String what) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown " + what + " '" + raw + "'. Use one of: "
                + String.join(", ", java.util.Arrays.stream(type.getEnumConstants()).map(Enum::name).toList()) + ".");
        }
    }
}
