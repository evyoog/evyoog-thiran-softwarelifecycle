package com.vyoog.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.identity.KnownServiceScopes;
import com.vyoog.integration.planner.PlannerRequirementIntake;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * VYB-0915 (D24, D50): where the Agile Planner sends a Function to be created here as a draft requirement. The wire shape is the
 * Planner's own documented contract (snake_case names, {@code Idempotency-Key}, a {@code requirement_id}, {@code status} and
 * {@code traceability} back), not this application's usual one, so the Planner can be pointed at this address as it is.
 *
 * <p>Caller: a registered service account holding {@code planner:requirements} (the Planner's OAuth2 client-credentials token), never a
 * person. {@code X-TENANT-ID}, which the contract names, is not read: this application has one customer (D3).
 */
@RestController
@RequestMapping("/api/v1/planner/requirements")
public class PlannerRequirementController {

    private final PlannerRequirementIntake intake;
    private final PrincipalGuard guard;

    public PlannerRequirementController(PlannerRequirementIntake intake, PrincipalGuard guard) {
        this.intake = intake;
        this.guard = guard;
    }

    public record PushBody(
        @JsonProperty("source_system") String sourceSystem,
        @JsonProperty("source_function_id") String sourceFunctionId,
        @JsonProperty("source_feature_id") String sourceFeatureId,
        @JsonProperty("source_capability_id") String sourceCapabilityId,
        @JsonProperty("source_goal_id") String sourceGoalId,
        @JsonProperty("title") String title,
        @JsonProperty("description") String description,
        @JsonProperty("priority") String priority,
        @JsonProperty("acceptance_criteria") List<String> acceptanceCriteria) {}

    public record Traceability(@JsonProperty("source_function_id") String sourceFunctionId) {}

    public record PushResponse(
        @JsonProperty("requirement_id") String requirementId,
        @JsonProperty("status") String status,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("traceability") Traceability traceability) {}

    /** 201 when this call created the requirement; 200 when it is a repeat of one already received. */
    @PostMapping
    public ResponseEntity<PushResponse> receive(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody PushBody body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireServiceAccountScope(jwt, KnownServiceScopes.PLANNER_REQUIREMENTS);
        var outcome = intake.receive(idempotencyKey, new PlannerRequirementIntake.Push(
            body.sourceSystem(), body.sourceFunctionId(), body.sourceFeatureId(), body.sourceCapabilityId(),
            body.sourceGoalId(), body.title(), body.description(), body.priority(), body.acceptanceCriteria()));
        var response = new PushResponse(outcome.key(), outcome.status().toLowerCase(java.util.Locale.ROOT), outcome.createdAt(),
            new Traceability(body.sourceFunctionId().strip()));
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(response);
    }
}
