package com.vyoog.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.attachments.AttachmentRejectedException;
import com.vyoog.attachments.AttachmentService;
import com.vyoog.deployment.DeploymentService;
import com.vyoog.evidence.CommitIngestService;
import com.vyoog.evidence.VerificationService;
import com.vyoog.identity.BootstrapRefusedException;
import com.vyoog.identity.GrantResolver;
import com.vyoog.identity.KnownServiceScopes;
import com.vyoog.identity.ServiceAccount;
import com.vyoog.identity.ServiceAccountChecker;
import com.vyoog.identity.ServiceAccountRefusedException;
import com.vyoog.identity.ServiceAccountRepository;
import com.vyoog.identity.ServiceAccountRequiredException;
import com.vyoog.identity.StepUpChecker;
import com.vyoog.identity.TenantBootstrapService;
import com.vyoog.identity.AppUser;
import com.vyoog.identity.UserProvisioningService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * VYB-0901 (F04/F05/F06): one attack case per door — each one is refused, and the legitimate
 * caller still gets through. Controllers are exercised directly with a real {@link PrincipalGuard}
 * and {@link ServiceAccountChecker}; only repositories and downstream services are mocked.
 */
class Vyb0901OpenDoorsTest {

    private final ServiceAccountRepository accounts = mock(ServiceAccountRepository.class);
    private final UserProvisioningService provisioning = mock(UserProvisioningService.class);
    private final PrincipalGuard guard = new PrincipalGuard(
        new ServiceAccountChecker(accounts), mock(StepUpChecker.class), provisioning, mock(GrantResolver.class));

    private static Jwt jwt(String azp, String email) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject("sub-1").claim("azp", azp)
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (email != null) b.claim("email", email);
        return b.build();
    }

    private void register(String clientId, String... scopes) {
        when(accounts.findByClientId(clientId))
            .thenReturn(Optional.of(new ServiceAccount("ci", "test", clientId, List.of(scopes))));
    }

    // ---- service accounts and CI ingest -------------------------------------------------

    private CiIngestController ci() {
        return new CiIngestController(mock(VerificationService.class), mock(CommitIngestService.class),
            mock(DeploymentService.class), guard);
    }

    @Test
    void VYB0901_AC2_anUnregisteredTokenWithNoEmailCannotPostToAnyCiEndpoint() {
        Jwt stranger = jwt("some-other-client", null);
        var c = ci();
        var run = new CiIngestController.TestRunBody("b1", "ci", List.of());
        var commit = new CiIngestController.CommitBody("abc", "m", null);
        var dep = new CiIngestController.DeploymentBody(UUID.randomUUID().toString(), "b1", true, List.of());
        assertThatThrownBy(() -> c.ingestTestRun(run, stranger)).isInstanceOf(ServiceAccountRequiredException.class);
        assertThatThrownBy(() -> c.ingestCommit(commit, stranger)).isInstanceOf(ServiceAccountRequiredException.class);
        assertThatThrownBy(() -> c.ingestDeployment(dep, stranger)).isInstanceOf(ServiceAccountRequiredException.class);
    }

    @Test
    void VYB0901_AC2_aRegisteredAccountWithoutTheCiIngestScopeIsRefused() {
        register("webhook-only", KnownServiceScopes.WEBHOOK_GIT);
        var c = ci();
        assertThatThrownBy(() -> c.ingestCommit(new CiIngestController.CommitBody("abc", "m", null), jwt("webhook-only", null)))
            .isInstanceOf(ServiceAccountRequiredException.class).hasMessageContaining("ci:ingest");
    }

    @Test
    void VYB0901_AC2_aPersonWithAnEmailCannotUseCiEndpoints() {
        assertThatThrownBy(() -> ci().ingestCommit(new CiIngestController.CommitBody("abc", "m", null),
            jwt("vyoog-web", "a@example.com"))).isInstanceOf(ServiceAccountRequiredException.class);
    }

    @Test
    void VYB0901_AC2_aRegisteredAccountHoldingCiIngestGetsThrough() {
        register("ci-bot", KnownServiceScopes.CI_INGEST);
        CommitIngestService commits = mock(CommitIngestService.class);
        when(commits.ingest(any(), any(), any())).thenReturn(new CommitIngestService.IngestResult(false, List.of(), List.of()));
        var c = new CiIngestController(mock(VerificationService.class), commits, mock(DeploymentService.class), guard);
        assertThatCode(() -> c.ingestCommit(new CiIngestController.CommitBody("abc", "m", null), jwt("ci-bot", null)))
            .doesNotThrowAnyException();
        verify(commits).ingest(any(), any(), any());
    }

    @Test
    void VYB0901_AC2_aTokenWithNoEmailAndNoRegistrationIsNotAPersonEither() {
        assertThatThrownBy(() -> guard.requireHuman(jwt("some-other-client", null)))
            .isInstanceOf(ServiceAccountRefusedException.class);
        register("ci-bot");
        assertThatThrownBy(() -> guard.requireHuman(jwt("ci-bot", "x@example.com")))
            .isInstanceOf(ServiceAccountRefusedException.class);
        assertThatCode(() -> guard.requireHuman(jwt("vyoog-web", "a@example.com"))).doesNotThrowAnyException();
    }

    // ---- bootstrap ----------------------------------------------------------------------

    private final TenantBootstrapService bootstrap = mock(TenantBootstrapService.class);

    private SettingsController settings(String configuredToken) {
        SettingsController c = new SettingsController(null, null, null, provisioning, null, guard, bootstrap, null, null, null);
        ReflectionTestUtils.setField(c, "bootstrapToken", configuredToken);
        AppUser u = mock(AppUser.class);
        when(u.getId()).thenReturn(UUID.randomUUID());
        when(provisioning.upsert(any(), any(), any())).thenReturn(u);
        return c;
    }

    private static final SettingsController.BootstrapRequest REQ = new SettingsController.BootstrapRequest(UUID.randomUUID());

    @Test
    void VYB0901_AC1_bootstrapIsOffWhenNoTokenIsConfigured() {
        SettingsController c = settings("");
        assertThatThrownBy(() -> c.bootstrapTenant(REQ, "anything", jwt("vyoog-web", "a@example.com")))
            .isInstanceOf(BootstrapRefusedException.class).hasMessageContaining("not enabled");
        assertThatThrownBy(() -> c.bootstrapTenant(REQ, "", jwt("vyoog-web", "a@example.com")))
            .isInstanceOf(BootstrapRefusedException.class);
        assertThatThrownBy(() -> c.bootstrapTenant(REQ, null, jwt("vyoog-web", "a@example.com")))
            .isInstanceOf(BootstrapRefusedException.class);
        verify(bootstrap, never()).bootstrap(any(), any());
    }

    @Test
    void VYB0901_AC1_aMissingOrWrongTokenIsRefused() {
        SettingsController c = settings("s3cret-token");
        assertThatThrownBy(() -> c.bootstrapTenant(REQ, null, jwt("vyoog-web", "a@example.com")))
            .isInstanceOf(BootstrapRefusedException.class);
        assertThatThrownBy(() -> c.bootstrapTenant(REQ, "s3cret-toke", jwt("vyoog-web", "a@example.com")))
            .isInstanceOf(BootstrapRefusedException.class)
            .hasMessageNotContaining("s3cret");
        verify(bootstrap, never()).bootstrap(any(), any());
    }

    @Test
    void VYB0901_AC1_aServiceAccountCannotBootstrapEvenWithTheToken() {
        register("ci-bot");
        SettingsController c = settings("tok");
        assertThatThrownBy(() -> c.bootstrapTenant(REQ, "tok", jwt("ci-bot", null)))
            .isInstanceOf(ServiceAccountRefusedException.class);
        verify(bootstrap, never()).bootstrap(any(), any());
    }

    @Test
    void VYB0901_AC1_aPersonWithTheRightTokenReachesTheBootstrapService() {
        SettingsController c = settings("tok");
        c.bootstrapTenant(REQ, "tok", jwt("vyoog-web", "a@example.com"));
        verify(bootstrap).bootstrap(any(), any());
    }

    @Test
    void VYB0901_AC1_bootstrapRefusalIsA403() {
        ProblemDetail pd = new ApiExceptionHandler().onBootstrapRefused(new BootstrapRefusedException("no"));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    // ---- attachments --------------------------------------------------------------------

    private AttachmentController attachments(AttachmentService service) {
        return new AttachmentController(service, provisioning, guard);
    }

    @Test
    void VYB0901_AC3_aServiceAccountOrEmailLessTokenCannotDownloadOrListAttachments() {
        AttachmentService service = mock(AttachmentService.class);
        var c = attachments(service);
        UUID req = UUID.randomUUID(), att = UUID.randomUUID();
        register("ci-bot", KnownServiceScopes.CI_INGEST);
        for (Jwt caller : new Jwt[] {jwt("ci-bot", null), jwt("stranger", null)}) {
            assertThatThrownBy(() -> c.downloadCurrent(req, att, caller)).isInstanceOf(ServiceAccountRefusedException.class);
            assertThatThrownBy(() -> c.downloadVersion(req, att, (short) 1, caller)).isInstanceOf(ServiceAccountRefusedException.class);
            assertThatThrownBy(() -> c.versions(req, att, caller)).isInstanceOf(ServiceAccountRefusedException.class);
            assertThatThrownBy(() -> c.list(req, caller)).isInstanceOf(ServiceAccountRefusedException.class);
        }
        verify(service, never()).downloadCurrent(any(), any());
    }

    @Test
    void VYB0901_AC3_theControllerPassesTheRequirementInThePathToTheOwnershipCheck() {
        AttachmentService service = mock(AttachmentService.class);
        UUID req = UUID.randomUUID(), att = UUID.randomUUID();
        when(service.downloadCurrent(req, att)).thenReturn(new AttachmentService.Downloaded(new byte[] {1}, "text/plain", "a.txt"));
        var response = attachments(service).downloadCurrent(req, att, jwt("vyoog-web", "a@example.com"));
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).startsWith("attachment");
        verify(service).downloadCurrent(req, att);
    }

    @Test
    void VYB0901_AC3_rejectedUploadsMapTo413And415And400() {
        var h = new ApiExceptionHandler();
        assertThat(h.onAttachmentRejected(new AttachmentRejectedException(AttachmentRejectedException.Reason.TOO_LARGE, "x")).getStatus()).isEqualTo(413);
        assertThat(h.onAttachmentRejected(new AttachmentRejectedException(AttachmentRejectedException.Reason.TYPE_NOT_ALLOWED, "x")).getStatus()).isEqualTo(415);
        assertThat(h.onAttachmentRejected(new AttachmentRejectedException(AttachmentRejectedException.Reason.BAD_FILENAME, "x")).getStatus()).isEqualTo(400);
        assertThat(h.onUploadTooLarge(new MaxUploadSizeExceededException(1)).getStatus()).isEqualTo(413);
    }

    @Test
    void VYB0901_AC3_multipartLimitsAreExplicitInApplicationYml() throws Exception {
        var yml = new org.springframework.boot.env.YamlPropertySourceLoader()
            .load("application", new org.springframework.core.io.ClassPathResource("application.yml")).get(0);
        assertThat(yml.getProperty("spring.servlet.multipart.max-file-size")).isEqualTo("10MB");
        assertThat(yml.getProperty("spring.servlet.multipart.max-request-size")).isEqualTo("12MB");
        assertThat(yml.getProperty("vyoog.attachments.max-bytes")).isEqualTo("${ATTACHMENT_MAX_BYTES:10485760}");
        assertThat(yml.getProperty("vyoog.bootstrap.token")).isEqualTo("${BOOTSTRAP_TOKEN:}");
    }
}
