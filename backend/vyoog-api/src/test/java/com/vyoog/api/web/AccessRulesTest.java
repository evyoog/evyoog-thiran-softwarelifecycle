package com.vyoog.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vyoog.api.config.AccessInterceptor;
import com.vyoog.api.config.AccessScopeResolver;
import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.api.config.RequiresAccess;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.AccessRule;
import com.vyoog.identity.AppUser;
import com.vyoog.identity.GrantResolver;
import com.vyoog.identity.ScopeType;
import com.vyoog.identity.ServiceAccount;
import com.vyoog.identity.ServiceAccountChecker;
import com.vyoog.identity.ServiceAccountRepository;
import com.vyoog.identity.StepUpChecker;
import com.vyoog.identity.UserProvisioningService;
import com.vyoog.requirements.AcceptanceCriterion;
import com.vyoog.requirements.AcceptanceCriterionRepository;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * VYB-0906 (F02): the roles matrix, exercised on every annotated endpoint at once.
 *
 * <p>The endpoints are found by reflection, so a newly annotated endpoint is tested the moment it
 * exists and there is no per-endpoint test to forget. For each one, a person holding each of the
 * nine roles (and one holding none, an administrator, and a registered service account) calls it
 * with an empty JSON body. The expectation comes straight from {@link AccessRule}: allowed if the
 * role is one the rule names, or the caller is an administrator; for {@link AccessRule#PERSON} any
 * signed-in person; never a service account. "Refused" is a real 403 through
 * {@link ApiExceptionHandler}; "allowed" is anything else, since the services behind the
 * controllers are mocks and an empty body is not expected to succeed.
 */
class AccessRulesTest {

    private final GrantResolver grants = mock(GrantResolver.class);
    private final UserProvisioningService provisioning = mock(UserProvisioningService.class);
    private final ServiceAccountRepository accounts = mock(ServiceAccountRepository.class);
    private final PrincipalGuard guard = new PrincipalGuard(
        new ServiceAccountChecker(accounts), mock(StepUpChecker.class), provisioning, grants);

    private final MockMvc mvc;
    private final List<AccessPolicyTest.Write> annotated = new ArrayList<>();

    AccessRulesTest() {
        for (var w : AccessPolicyTest.writeEndpoints()) {
            if (w.method().isAnnotationPresent(RequiresAccess.class)) annotated.add(w);
        }
        // every user: one per role, plus none and admin
        for (AccessRole role : AccessRole.values()) {
            UUID id = person("role-" + role);
            when(grants.holdsRoleAnywhere(id, role)).thenReturn(true);
            when(grants.hasEffectiveRole(eq(id), eq(role), any(), any())).thenReturn(true);
            // the real resolver defines a platform administrator as holding ADMINISTRATOR at platform level
            if (role == AccessRole.ADMINISTRATOR) when(grants.isPlatformAdministrator(id)).thenReturn(true);
        }
        person("none");
        UUID admin = person("admin");
        when(grants.isPlatformAdministrator(admin)).thenReturn(true);
        when(grants.hasEffectiveRole(admin, AccessRole.ADMINISTRATOR, ScopeType.PLATFORM, null)).thenReturn(true);
        when(accounts.findByClientId("ci-bot"))
            .thenReturn(Optional.of(new ServiceAccount("ci", "t", "ci-bot", List.of("ci:ingest"))));

        // scoped targets resolve to something so the scoped branch runs; scope itself is AccessScopeTest's job
        var requirements = mock(RequirementRepository.class);
        var req = mock(Requirement.class);
        when(req.getCapabilityId()).thenReturn(UUID.randomUUID());
        when(requirements.findById(any())).thenReturn(Optional.of(req));
        var criteria = mock(AcceptanceCriterionRepository.class);
        var criterion = mock(AcceptanceCriterion.class);
        when(criterion.getRequirementId()).thenReturn(UUID.randomUUID());
        when(criteria.findById(any())).thenReturn(Optional.of(criterion));
        var batches = mock(com.vyoog.importqueue.ImportBatchRepository.class);
        var batch = mock(com.vyoog.importqueue.ImportBatch.class);
        when(batch.getApplicationId()).thenReturn(UUID.randomUUID());
        when(batches.findById(any())).thenReturn(Optional.of(batch));
        var candidates = mock(com.vyoog.importqueue.ImportCandidateRepository.class);
        var candidate = mock(com.vyoog.importqueue.ImportCandidate.class);
        when(candidate.getBatchId()).thenReturn(UUID.randomUUID());
        when(candidates.findById(any())).thenReturn(Optional.of(candidate));
        var analyses = mock(com.vyoog.importqueue.DocumentAnalysisRepository.class);
        var analysis = mock(com.vyoog.importqueue.DocumentAnalysis.class);
        when(analysis.getBatchId()).thenReturn(UUID.randomUUID());
        when(analyses.findById(any())).thenReturn(Optional.of(analysis));
        var interceptor = new AccessInterceptor(guard,
            new AccessScopeResolver(requirements, criteria, batches, candidates, analyses));

        Set<Class<?>> controllers = new LinkedHashSet<>();
        annotated.forEach(w -> controllers.add(w.controller()));
        List<Object> instances = new ArrayList<>();
        controllers.forEach(c -> instances.add(build(c)));
        mvc = MockMvcBuilders.standaloneSetup(instances.toArray())
            .setControllerAdvice(new ApiExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .addInterceptors(interceptor)
            .build();
    }

    private UUID person(String sub) {
        UUID id = UUID.randomUUID();
        AppUser u = mock(AppUser.class);
        when(u.getId()).thenReturn(id);
        when(provisioning.upsert(eq("sub-" + sub), any(), any())).thenReturn(u);
        return id;
    }

    private Object build(Class<?> type) {
        try {
            Constructor<?> ctor = type.getConstructors()[0];
            Object[] args = new Object[ctor.getParameterCount()];
            Class<?>[] types = ctor.getParameterTypes();
            for (int i = 0; i < args.length; i++) {
                args[i] = types[i] == PrincipalGuard.class ? guard
                    : types[i] == UserProvisioningService.class ? provisioning
                    : mock(types[i]);
            }
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot build " + type.getSimpleName(), e);
        }
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // ---- request building ----------------------------------------------------------------

    private static final Pattern VAR = Pattern.compile("\\{[^}]+}");

    private static MockHttpServletRequestBuilder request(AccessPolicyTest.Write w) {
        String base = w.controller().isAnnotationPresent(RequestMapping.class)
            ? first(w.controller().getAnnotation(RequestMapping.class).value()) : "";
        HttpMethod verb;
        String sub;
        Method m = w.method();
        String consumes = "application/json";
        if (m.isAnnotationPresent(PostMapping.class) && m.getAnnotation(PostMapping.class).consumes().length > 0) {
            consumes = m.getAnnotation(PostMapping.class).consumes()[0]; // e.g. the multipart upload
        }
        if (m.isAnnotationPresent(PostMapping.class)) { verb = HttpMethod.POST; sub = first(m.getAnnotation(PostMapping.class).value()); }
        else if (m.isAnnotationPresent(PutMapping.class)) { verb = HttpMethod.PUT; sub = first(m.getAnnotation(PutMapping.class).value()); }
        else if (m.isAnnotationPresent(PatchMapping.class)) { verb = HttpMethod.PATCH; sub = first(m.getAnnotation(PatchMapping.class).value()); }
        else { verb = HttpMethod.DELETE; sub = first(m.getAnnotation(DeleteMapping.class).value()); }
        String path = VAR.matcher(base + sub).replaceAll(r -> UUID.randomUUID().toString());
        return MockMvcRequestBuilders.request(verb, path).contentType(consumes).content("{}");
    }

    private static String first(String[] values) {
        return values.length == 0 ? "" : values[0];
    }

    private static Jwt jwt(String sub, String azp, String email) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject("sub-" + sub).claim("azp", azp)
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (email != null) b.claim("email", email);
        return b.build();
    }

    /** true = refused with 403 by the access rule. */
    private boolean refused(AccessPolicyTest.Write w, Jwt token) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
        try {
            return mvc.perform(request(w)).andReturn().getResponse().getStatus() == 403;
        } catch (Exception e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                for (StackTraceElement el : t.getStackTrace()) {
                    if (el.getClassName().endsWith("AccessInterceptor")) {
                        throw new AssertionError(w.key() + ": the interceptor itself failed", e);
                    }
                }
            }
            return false; // the handler ran and failed on the empty body, which means it was let in
        }
    }

    private static boolean allowedByRule(RequiresAccess a, AccessRole role) {
        AccessRule rule = a.value();
        if (role == AccessRole.ADMINISTRATOR) return true;
        if (rule == AccessRule.PERSON) return true;
        return rule.roles().contains(role);
    }

    // ---- tests ----------------------------------------------------------------------------

    @Test
    void VYB0906_AC3_everyAnnotatedEndpointGives403ToAnOrdinaryUserAndToAServiceAccount() throws Exception {
        assertThat(annotated).hasSizeGreaterThanOrEqualTo(87);
        for (var w : annotated) {
            RequiresAccess a = w.method().getAnnotation(RequiresAccess.class);
            Jwt serviceAccount = Jwt.withTokenValue("t").header("alg", "none").subject("sub-ci").claim("azp", "ci-bot")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
            assertThat(refused(w, serviceAccount)).as("%s: service account", w.key()).isTrue();
            if (a.value() != AccessRule.PERSON) {
                assertThat(refused(w, jwt("none", "vyoog-web", "none@example.com")))
                    .as("%s: a person with no role", w.key()).isTrue();
            }
        }
    }

    @Test
    void VYB0906_AC3_eachRoleIsAllowedExactlyWhereTheMatrixSaysAndAdministratorEverywhere() throws Exception {
        for (var w : annotated) {
            RequiresAccess a = w.method().getAnnotation(RequiresAccess.class);
            for (AccessRole role : AccessRole.values()) {
                String sub = "role-" + role;
                boolean expectRefused = !allowedByRule(a, role);
                assertThat(refused(w, jwt(sub, "vyoog-web", sub + "@example.com")))
                    .as("%s as %s (rule %s)", w.key(), role, a.value())
                    .isEqualTo(expectRefused);
            }
        }
    }

    @Test
    void VYB0906_AC3_anAdministratorPassesEveryRule() throws Exception {
        for (var w : annotated) {
            assertThat(refused(w, jwt("admin", "vyoog-web", "admin@example.com"))).as(w.key()).isFalse();
        }
    }

    @Test
    void VYB0906_AC3_aTokenWithNoEmailAndNoRegistrationIsRefusedEverywhere() throws Exception {
        for (var w : annotated) {
            assertThat(refused(w, jwt("role-ADMINISTRATOR", "some-other-client", null))).as(w.key()).isTrue();
        }
    }

    @Test
    void VYB0906_AC4_theMatrixIsWhatTheRolesScreenPromises() {
        assertThat(AccessRule.CREATE_EDIT_REQ.roles()).containsExactlyInAnyOrder(AccessRole.BUSINESS_ANALYST, AccessRole.ARCHITECT);
        assertThat(AccessRule.REVIEW.roles()).containsExactlyInAnyOrder(
            AccessRole.REVIEWER, AccessRole.APPROVER, AccessRole.COMPLIANCE_LEAD, AccessRole.ARCHITECT);
        assertThat(AccessRule.APPROVE.roles()).containsExactly(AccessRole.APPROVER);
        assertThat(AccessRule.VERIFY.roles()).containsExactly(AccessRole.TESTER);
        assertThat(AccessRule.BASELINE.roles()).containsExactly(AccessRole.APPROVER);
        assertThat(AccessRule.ADMIN.roles()).isEmpty();
        assertThat(AccessRule.PERSON.roles()).isEmpty();
    }
}
