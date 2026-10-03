package com.vyoog.api.web;

import com.vyoog.api.config.RequiresAccess;
import com.vyoog.identity.AccessRule;
import com.vyoog.changerequest.ChangeRequest;
import com.vyoog.changerequest.ChangeRequestService;
import com.vyoog.clarification.Clarification;
import com.vyoog.clarification.ClarificationService;
import com.vyoog.identity.UserProvisioningService;
import com.vyoog.platform.config.AppConfigService;
import jakarta.validation.constraints.NotBlank;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0330–0334/0380–0382. */
@RestController
@RequestMapping("/api/v1")
public class ClarificationController {

    private final ClarificationService service;
    private final ChangeRequestService changeRequests;
    private final UserProvisioningService provisioning;
    private final AppConfigService appConfig;

    public ClarificationController(ClarificationService service, ChangeRequestService changeRequests,
                                    UserProvisioningService provisioning, AppConfigService appConfig) {
        this.service = service;
        this.changeRequests = changeRequests;
        this.provisioning = provisioning;
        this.appConfig = appConfig;
    }

    private UUID currentUserId(Jwt jwt) {
        return provisioning.upsert(
            jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username")).getId();
    }

    public record ClarificationView(
        String id, String requirementId, String question, boolean blocksTask, String raisedBy, String raisedAt,
        String assignedTo, String answer, String answeredBy, String answeredAt, String state,
        String resultedInChangeRequestId, String escalatedAt, String escalatedTo, String dueAt) {}

    /** VYB-0380 AC1: blocking defaults to true when the caller doesn't say otherwise. */
    public record RaiseClarification(@NotBlank String question, String assignedTo, Boolean blocksTask) {}
    /**
     * VYB-0333 AC2: the change-request path is offered, never forced — {@code
     * changeRequestTitle} present means the caller (the frontend, after the user
     * confirms) chose to take it; absent, this is a plain answer.
     */
    public record AnswerClarification(@NotBlank String answer, String changeRequestTitle, String changeRequestRationale) {}

    /**
     * VYB-0372 (session 14): "due" is derived, never stored — {@code raisedAt} plus
     * the configured escalation window, the exact threshold
     * {@code ClarificationService.escalateAgeing} already enforces (V005/session 9).
     * Only meaningful while still OPEN; a resolved one isn't "due" anymore.
     */
    private ClarificationView toView(Clarification c) {
        String dueAt = c.getState() == com.vyoog.clarification.ClarificationState.OPEN
            ? c.getRaisedAt().plus(appConfig.clarificationEscalationDays(), ChronoUnit.DAYS).toString()
            : null;
        return new ClarificationView(
            c.getId().toString(), c.getRequirementId().toString(), c.getQuestion(), c.isBlocksTask(),
            c.getRaisedBy().toString(), c.getRaisedAt().toString(),
            c.getAssignedTo() == null ? null : c.getAssignedTo().toString(),
            c.getAnswer(), c.getAnsweredBy() == null ? null : c.getAnsweredBy().toString(),
            c.getAnsweredAt() == null ? null : c.getAnsweredAt().toString(), c.getState().name(),
            c.getResultedInChangeRequestId() == null ? null : c.getResultedInChangeRequestId().toString(),
            c.getEscalatedAt() == null ? null : c.getEscalatedAt().toString(),
            c.getEscalatedTo() == null ? null : c.getEscalatedTo().toString(), dueAt);
    }

    @GetMapping("/requirements/{requirementId}/clarifications")
    public List<ClarificationView> forRequirement(@PathVariable UUID requirementId) {
        return service.forRequirement(requirementId).stream().map(this::toView).toList();
    }

    // VYB-0906: anyone reading a requirement may ask a question.
    @RequiresAccess(AccessRule.PERSON)
    @PostMapping("/requirements/{requirementId}/clarifications")
    @ResponseStatus(HttpStatus.CREATED)
    public ClarificationView raise(@PathVariable UUID requirementId, @RequestBody RaiseClarification body,
                                    @AuthenticationPrincipal Jwt jwt) {
        Clarification c = service.raise(requirementId, body.question(),
            body.assignedTo() == null ? null : UUID.fromString(body.assignedTo()),
            body.blocksTask() == null || body.blocksTask(), currentUserId(jwt));
        return toView(c);
    }

    /**
     * VYB-0381 AC1: enforced in the service — only the assignee or their delegate
     * succeeds. VYB-0333 AC1: {@code resultedInChangeRequestId} on the response is
     * set only when the caller opted into the change-request path.
     */
    // VYB-0906: answered by whoever edits the requirement.
    @RequiresAccess(value = AccessRule.CREATE_EDIT_REQ, scope = RequiresAccess.Scope.ANYWHERE)
    @PostMapping("/clarifications/{id}/answer")
    public ClarificationView answer(@PathVariable UUID id, @RequestBody AnswerClarification body,
                                     @AuthenticationPrincipal Jwt jwt) {
        UUID actor = currentUserId(jwt);
        Clarification answered = service.answer(id, body.answer(), actor);
        if (body.changeRequestTitle() != null && !body.changeRequestTitle().isBlank()) {
            ChangeRequest cr = changeRequests.raise(
                body.changeRequestTitle(),
                body.changeRequestRationale() == null
                    ? "Raised from clarification: " + body.answer() : body.changeRequestRationale(),
                List.of(answered.getRequirementId()), actor);
            service.linkToChangeRequest(answered.getId(), cr.getId());
            UUID answeredId = answered.getId();
            answered = service.forRequirement(answered.getRequirementId()).stream()
                .filter(x -> x.getId().equals(answeredId)).findFirst().orElse(answered);
        }
        return toView(answered);
    }

    @GetMapping("/clarifications/blocking")
    public List<ClarificationView> openBlocking() {
        return service.openBlocking().stream().map(this::toView).toList();
    }
}
