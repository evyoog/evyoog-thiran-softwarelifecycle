package com.vyoog.api.web;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.deployment.Deployment;
import com.vyoog.deployment.DeploymentService;
import com.vyoog.evidence.CommitIngestService;
import com.vyoog.evidence.TestRun;
import com.vyoog.evidence.VerificationResult;
import com.vyoog.evidence.VerificationService;
import com.vyoog.identity.KnownServiceScopes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * VYB-0311/0315/0316/0481: everything a CI system pushes in, not a person — {@link
 * PrincipalGuard#requireServiceAccountScope} refuses any caller that is not a registered
 * service account holding the {@code ci:ingest} scope (VYB-0901: registration and scope are
 * both required; a token with merely no email claim is not enough).
 */
@RestController
@RequestMapping("/api/v1/ci")
public class CiIngestController {

    private final VerificationService verification;
    private final CommitIngestService commits;
    private final DeploymentService deployment;
    private final PrincipalGuard guard;

    public CiIngestController(VerificationService verification, CommitIngestService commits,
                               DeploymentService deployment, PrincipalGuard guard) {
        this.verification = verification;
        this.commits = commits;
        this.deployment = deployment;
        this.guard = guard;
    }

    public record ResultBody(@NotBlank String testKey, String title, @NotBlank String result, List<String> requirementKeys) {}
    public record TestRunBody(String buildLabel, String source, @NotEmpty List<ResultBody> results) {}
    public record IngestOutcomeView(String testKey, boolean testCaseWasUnknown, List<String> verifiedRequirementKeys) {}
    public record TestRunResponse(String runId, List<IngestOutcomeView> outcomes) {}

    @PostMapping("/test-runs")
    @ResponseStatus(HttpStatus.CREATED)
    public TestRunResponse ingestTestRun(@RequestBody TestRunBody body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireServiceAccountScope(jwt, KnownServiceScopes.CI_INGEST);
        TestRun run = verification.findOrCreateRun(body.buildLabel(), body.source());
        List<IngestOutcomeView> outcomes = body.results().stream()
            .map(r -> verification.ingest(run, new VerificationService.ResultInput(
                r.testKey(), r.title(), VerificationResult.valueOf(r.result()), r.requirementKeys())))
            .map(o -> new IngestOutcomeView(o.testKey(), o.testCaseWasUnknown(), o.verifiedRequirementKeys()))
            .toList();
        return new TestRunResponse(run.getId().toString(), outcomes);
    }

    public record CommitBody(@NotBlank String sha, String message, String authorEmail) {}
    public record CommitResponse(boolean alreadyIngested, List<String> linkedKeys, List<String> unknownKeys) {}

    @PostMapping("/commits")
    @ResponseStatus(HttpStatus.CREATED)
    public CommitResponse ingestCommit(@RequestBody CommitBody body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireServiceAccountScope(jwt, KnownServiceScopes.CI_INGEST);
        var result = commits.ingest(body.sha(), body.message(), body.authorEmail());
        return new CommitResponse(result.alreadyIngested(), result.linkedKeys(), result.unknownKeys());
    }

    /** VYB-0481 AC1/AC2: service-account only; a failed deployment is recorded, not rejected. */
    public record DeploymentBody(
        @NotBlank String environmentId, @NotBlank String buildLabel, boolean succeeded, List<String> commitShas) {}
    public record DeploymentResponse(String deploymentId) {}

    @PostMapping("/deployments")
    @ResponseStatus(HttpStatus.CREATED)
    public DeploymentResponse ingestDeployment(@RequestBody DeploymentBody body, @AuthenticationPrincipal Jwt jwt) {
        guard.requireServiceAccountScope(jwt, KnownServiceScopes.CI_INGEST);
        Deployment d = deployment.recordDeployment(
            UUID.fromString(body.environmentId()), body.buildLabel(), body.succeeded(), body.commitShas());
        return new DeploymentResponse(d.getId().toString());
    }
}
