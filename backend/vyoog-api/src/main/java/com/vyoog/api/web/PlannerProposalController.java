package com.vyoog.api.web;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.api.config.RequiresAccess;
import com.vyoog.identity.AccessRule;
import com.vyoog.identity.UserProvisioningService;
import com.vyoog.integration.planner.PlannerProposalService;
import com.vyoog.integration.planner.PlannerProposalService.PlannerProposalView;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * VYB-0919 (D24, D51): the changes the Agile Planner proposed to a requirement it created, and a person's decision on each. Reading is
 * for any signed-in person; deciding needs the same role as editing the requirement, where it sits (and is never a service account).
 */
@RestController
@RequestMapping("/api/v1/requirements/{id}/planner-proposals")
public class PlannerProposalController {

    private final PlannerProposalService proposals;
    private final UserProvisioningService provisioning;
    private final PrincipalGuard guard;

    public PlannerProposalController(PlannerProposalService proposals, UserProvisioningService provisioning, PrincipalGuard guard) {
        this.proposals = proposals;
        this.provisioning = provisioning;
        this.guard = guard;
    }

    public record RejectBody(String note) {}

    @GetMapping
    public List<PlannerProposalView> list(@PathVariable UUID id) {
        return proposals.list(id);
    }

    @RequiresAccess(value = AccessRule.CREATE_EDIT_REQ, scope = RequiresAccess.Scope.REQUIREMENT)
    @PostMapping("/{proposalId}/accept")
    public PlannerProposalView accept(@PathVariable UUID id, @PathVariable UUID proposalId, @AuthenticationPrincipal Jwt jwt) {
        guard.requireHuman(jwt);
        return proposals.accept(id, proposalId, currentUserId(jwt));
    }

    @RequiresAccess(value = AccessRule.CREATE_EDIT_REQ, scope = RequiresAccess.Scope.REQUIREMENT)
    @PostMapping("/{proposalId}/reject")
    public PlannerProposalView reject(@PathVariable UUID id, @PathVariable UUID proposalId, @RequestBody(required = false) RejectBody body,
                               @AuthenticationPrincipal Jwt jwt) {
        guard.requireHuman(jwt);
        return proposals.reject(id, proposalId, body == null ? null : body.note(), currentUserId(jwt));
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }
}
