package com.vyoog.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vyoog.api.config.PlannerLimits;
import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.api.config.StaleTokenException;
import com.vyoog.identity.KnownServiceScopes;
import com.vyoog.integration.planner.PlannerRequirementIntake;
import com.vyoog.platform.RateLimiter;
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
    private final PlannerLimits limits;
    private final RateLimiter rateLimiter;

    public PlannerRequirementController(PlannerRequirementIntake intake, PrincipalGuard guard, PlannerLimits limits, RateLimiter rateLimiter) {
        this.intake = intake;
        this.guard = guard;
        this.limits = limits;
        this.rateLimiter = rateLimiter;
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

    /** A change the Planner sent to a Function that already has a requirement: held for a person to accept or reject. */
    public record ProposalRef(
        @JsonProperty("proposal_id") String proposalId,
        @JsonProperty("state") String state) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PushResponse(
        @JsonProperty("requirement_id") String requirementId,
        @JsonProperty("status") String status,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("traceability") Traceability traceability,
        @JsonProperty("proposal") ProposalRef proposal) {}

    /**
     * 201 when this call created the requirement; 200 when it is a repeat of one already received; 202 when the Function had changed
     * and the change is held as a proposal (the requirement itself is untouched; its {@code status} is its current one).
     */
    @PostMapping
    public ResponseEntity<PushResponse> receive(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody PushBody body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireServiceAccountScope(jwt, KnownServiceScopes.PLANNER_REQUIREMENTS);
        requireFreshToken(jwt);
        // counted per service account, after the caller is known to be one (a refused call does not use a registered account's allowance)
        rateLimiter.requireWithinWindow("planner.requirements:" + jwt.getClaimAsString("azp"), limits.pushesPerMinute(), java.time.Duration.ofMinutes(1));
        var outcome = intake.receive(idempotencyKey, new PlannerRequirementIntake.Push(
            body.sourceSystem(), body.sourceFunctionId(), body.sourceFeatureId(), body.sourceCapabilityId(),
            body.sourceGoalId(), body.title(), body.description(), body.priority(), body.acceptanceCriteria()));
        var response = new PushResponse(outcome.key(), outcome.status().toLowerCase(java.util.Locale.ROOT), outcome.createdAt(),
            new Traceability(body.sourceFunctionId().strip()),
            outcome.proposed() ? new ProposalRef(outcome.proposalId().toString(), outcome.proposalState().toLowerCase(java.util.Locale.ROOT)) : null);
        HttpStatus code = outcome.created() ? HttpStatus.CREATED : outcome.proposed() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(code).body(response);
    }

    /**
     * A token older than {@code vyoog.planner.max-token-age-minutes} is refused, and so is one with no issue time or one issued in the
     * future (more than two minutes ahead, which is clock skew). A captured token is then useful for minutes, not for its whole lifetime.
     */
    private void requireFreshToken(Jwt jwt) {
        int maxMinutes = limits.maxTokenAgeMinutes();
        if (maxMinutes == 0) return;
        Instant issued = jwt.getIssuedAt();
        if (issued == null) {
            throw new StaleTokenException("The token has no issue time (iat), so its age cannot be checked; request a new one");
        }
        Instant now = Instant.now();
        if (issued.isAfter(now.plusSeconds(120))) {
            throw new StaleTokenException("The token was issued in the future; check the clocks and request a new one");
        }
        if (issued.isBefore(now.minus(java.time.Duration.ofMinutes(maxMinutes)))) {
            throw new StaleTokenException("The token is older than " + maxMinutes + " minutes; request a new one");
        }
    }
}
