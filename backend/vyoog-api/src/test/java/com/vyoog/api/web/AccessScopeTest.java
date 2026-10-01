package com.vyoog.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.vyoog.api.config.AccessInterceptor;
import com.vyoog.api.config.AccessScopeResolver;
import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.AppUser;
import com.vyoog.identity.GrantResolver;
import com.vyoog.identity.ScopeType;
import com.vyoog.identity.ServiceAccountChecker;
import com.vyoog.identity.ServiceAccountRepository;
import com.vyoog.identity.StepUpChecker;
import com.vyoog.identity.UserProvisioningService;
import com.vyoog.requirements.AcceptanceCriterion;
import com.vyoog.requirements.AcceptanceCriterionRepository;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import com.vyoog.requirements.RequirementService;
import com.vyoog.requirements.RequirementStatus;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * VYB-0906: where the role is checked. A grant on one capability does not open another; a grant on
 * the product above a capability does open it (the resolver walks up); a requirement or criterion
 * that does not exist is a 404, not an open door; and creating a requirement checks the placement
 * named in the body.
 */
class AccessScopeTest {

    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID APP = UUID.randomUUID();
    private static final UUID CAP = UUID.randomUUID();
    private static final UUID OTHER_CAP = UUID.randomUUID();
    private static final UUID REQ = UUID.randomUUID();
    private static final UUID CRITERION = UUID.randomUUID();
    private static final UUID OTHER_APP = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();
    private static final UUID CANDIDATE = UUID.randomUUID();
    private static final UUID ANALYSIS = UUID.randomUUID();

    private final GrantResolver grants = mock(GrantResolver.class);
    private final UserProvisioningService provisioning = mock(UserProvisioningService.class);
    private final PrincipalGuard guard = new PrincipalGuard(
        new ServiceAccountChecker(mock(ServiceAccountRepository.class)), mock(StepUpChecker.class), provisioning, grants);
    private final RequirementRepository requirements = mock(RequirementRepository.class);
    private final RequirementService requirementService = mock(RequirementService.class);
    private final MockMvc mvc;

    AccessScopeTest() {
        Requirement r = mock(Requirement.class);
        when(r.getId()).thenReturn(REQ);
        when(r.getCapabilityId()).thenReturn(CAP);
        when(r.getStatus()).thenReturn(RequirementStatus.DRAFT);
        when(requirements.findById(REQ)).thenReturn(Optional.of(r));
        when(requirementService.create(any(), any(), any(), any(), any(), any())).thenReturn(r);

        var criteria = mock(AcceptanceCriterionRepository.class);
        AcceptanceCriterion c = mock(AcceptanceCriterion.class);
        when(c.getRequirementId()).thenReturn(REQ);
        when(criteria.findById(CRITERION)).thenReturn(Optional.of(c));

        // an import batch uploaded into APP, with one candidate and one analysis
        var batches = mock(com.vyoog.importqueue.ImportBatchRepository.class);
        var batch = mock(com.vyoog.importqueue.ImportBatch.class);
        when(batch.getApplicationId()).thenReturn(APP);
        when(batches.findById(BATCH)).thenReturn(Optional.of(batch));
        var candidates = mock(com.vyoog.importqueue.ImportCandidateRepository.class);
        var candidate = mock(com.vyoog.importqueue.ImportCandidate.class);
        when(candidate.getBatchId()).thenReturn(BATCH);
        when(candidates.findById(CANDIDATE)).thenReturn(Optional.of(candidate));
        var analyses = mock(com.vyoog.importqueue.DocumentAnalysisRepository.class);
        var analysis = mock(com.vyoog.importqueue.DocumentAnalysis.class);
        when(analysis.getBatchId()).thenReturn(BATCH);
        when(analyses.findById(ANALYSIS)).thenReturn(Optional.of(analysis));
        user("ba-on-app", AccessRole.BUSINESS_ANALYST, ScopeType.APP, APP);
        user("ba-on-other-app", AccessRole.BUSINESS_ANALYST, ScopeType.APP, OTHER_APP);

        user("ba-on-cap", AccessRole.BUSINESS_ANALYST, ScopeType.CAPABILITY, CAP);
        user("ba-other-cap", AccessRole.BUSINESS_ANALYST, ScopeType.CAPABILITY, OTHER_CAP);
        user("ba-on-product", AccessRole.BUSINESS_ANALYST, ScopeType.PRODUCT, PRODUCT);
        // a product grant covers the capability beneath it: model the resolver's upward walk
        UUID onProduct = ids.get("ba-on-product");
        when(grants.holdsRoleAnywhere(onProduct, AccessRole.BUSINESS_ANALYST)).thenReturn(true);
        when(grants.hasEffectiveRole(eq(onProduct), eq(AccessRole.BUSINESS_ANALYST), eq(ScopeType.CAPABILITY), eq(CAP))).thenReturn(true);
        when(grants.hasEffectiveRole(eq(onProduct), eq(AccessRole.BUSINESS_ANALYST), eq(ScopeType.APP), eq(APP))).thenReturn(true);

        var interceptor = new AccessInterceptor(guard,
            new AccessScopeResolver(requirements, criteria, batches, candidates, analyses));
        mvc = MockMvcBuilders.standaloneSetup(build(RequirementController.class, requirements, requirementService),
                build(ImportController.class, requirements, requirementService))
            .setControllerAdvice(new ApiExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .addInterceptors(interceptor)
            .build();
    }

    private final java.util.Map<String, UUID> ids = new java.util.HashMap<>();

    private void user(String sub, AccessRole role, ScopeType type, UUID scopeId) {
        UUID id = UUID.randomUUID();
        ids.put(sub, id);
        AppUser u = mock(AppUser.class);
        when(u.getId()).thenReturn(id);
        when(provisioning.upsert(eq("sub-" + sub), any(), any())).thenReturn(u);
        when(grants.holdsRoleAnywhere(id, role)).thenReturn(true);
        when(grants.hasEffectiveRole(id, role, type, scopeId)).thenReturn(true);
    }

    private Object build(Class<?> type, RequirementRepository repo, RequirementService service) {
        try {
            Constructor<?> ctor = type.getConstructors()[0];
            Object[] args = new Object[ctor.getParameterCount()];
            Class<?>[] types = ctor.getParameterTypes();
            for (int i = 0; i < args.length; i++) {
                args[i] = types[i] == PrincipalGuard.class ? guard
                    : types[i] == UserProvisioningService.class ? provisioning
                    : types[i] == RequirementRepository.class ? repo
                    : types[i] == RequirementService.class ? service
                    : mock(types[i]);
            }
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private int as(String sub, MockHttpServletRequestBuilder request) throws Exception {
        Jwt token = Jwt.withTokenValue("t").header("alg", "none").subject("sub-" + sub).claim("azp", "vyoog-web")
            .claim("email", sub + "@example.com").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
        try {
            return mvc.perform(request).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            return -1; // the handler ran (it failed on a mock): the access rule let it through
        }
    }

    private MockHttpServletRequestBuilder addCriterion(UUID requirement) {
        return post("/api/v1/requirements/{id}/acceptance-criteria", requirement)
            .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"x\"}");
    }

    @Test
    void VYB0906_AC5_aGrantOnAnotherCapabilityDoesNotOpenThisRequirement() throws Exception {
        assertThat(as("ba-other-cap", addCriterion(REQ))).isEqualTo(403);
        assertThat(as("ba-other-cap", put("/api/v1/requirements/{id}/acceptance-criteria/order", REQ)
            .contentType(MediaType.APPLICATION_JSON).content("[]"))).isEqualTo(403);
    }

    @Test
    void VYB0906_AC5_aGrantOnTheCapabilityOrOnItsProductDoes() throws Exception {
        assertThat(as("ba-on-cap", addCriterion(REQ))).isNotEqualTo(403);
        assertThat(as("ba-on-product", addCriterion(REQ))).isNotEqualTo(403);
    }

    @Test
    void VYB0906_AC5_aCriterionIsCheckedAtItsRequirementsScope() throws Exception {
        var edit = patch("/api/v1/requirements/acceptance-criteria/{criterionId}", CRITERION)
            .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"y\"}");
        assertThat(as("ba-other-cap", edit)).isEqualTo(403);
        assertThat(as("ba-on-cap", edit)).isNotEqualTo(403);
    }

    @Test
    void VYB0906_AC5_anUnknownRequirementOrCriterionIsA404NotAnOpenDoor() throws Exception {
        assertThat(as("ba-on-cap", addCriterion(UUID.randomUUID()))).isEqualTo(404);
        assertThat(as("ba-on-cap", patch("/api/v1/requirements/acceptance-criteria/{criterionId}", UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON).content("{}"))).isEqualTo(404);
        assertThat(as("ba-on-cap", delete("/api/v1/requirements/{id}/acceptance-criteria/x", REQ))).isNotEqualTo(200);
    }

    @Test
    void VYB0906_AC5_creatingARequirementChecksThePlacementInTheBody() throws Exception {
        String intoOther = "{\"title\":\"t\",\"statement\":\"s\",\"capabilityId\":\"" + OTHER_CAP + "\"}";
        String intoOwn = "{\"title\":\"t\",\"statement\":\"s\",\"capabilityId\":\"" + CAP + "\"}";
        assertThat(as("ba-on-cap", post("/api/v1/requirements").contentType(MediaType.APPLICATION_JSON).content(intoOther)))
            .isEqualTo(403);
        assertThat(as("ba-on-cap", post("/api/v1/requirements").contentType(MediaType.APPLICATION_JSON).content(intoOwn)))
            .isNotEqualTo(403);
    }

    @Test
    void VYB0906_AC5_anUnplacedRequirementNeedsTheRoleSomewhere() throws Exception {
        String unplaced = "{\"title\":\"t\",\"statement\":\"s\"}";
        assertThat(as("ba-on-cap", post("/api/v1/requirements").contentType(MediaType.APPLICATION_JSON).content(unplaced)))
            .isNotEqualTo(403);
        UUID stranger = UUID.randomUUID();
        AppUser u = mock(AppUser.class);
        when(u.getId()).thenReturn(stranger);
        when(provisioning.upsert(eq("sub-nobody"), any(), any())).thenReturn(u);
        assertThat(as("nobody", post("/api/v1/requirements").contentType(MediaType.APPLICATION_JSON).content(unplaced)))
            .isEqualTo(403);
    }

    // ---- import steps are checked at the application the batch was uploaded to (6b) ----------

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b) {
        return b.contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    @Test
    void VYB0906_AC6_aCandidateStepNeedsTheRoleOnTheBatchsApplication() throws Exception {
        var step = json(post("/api/v1/import/candidates/{id}/confirm-type", CANDIDATE));
        assertThat(as("ba-on-other-app", step)).isEqualTo(403);
        assertThat(as("ba-on-app", step)).isNotEqualTo(403);
        assertThat(as("ba-on-cap", step)).isEqualTo(403); // a capability grant elsewhere is not on this application
    }

    @Test
    void VYB0906_AC6_aBatchStepAndAnAnalysisDecisionAreCheckedTheSameWay() throws Exception {
        var extract = json(post("/api/v1/import/batches/{id}/extract", BATCH));
        var accept = json(post("/api/v1/import/analysis/{id}/accept", ANALYSIS));
        var placement = json(post("/api/v1/import/batches/{batchId}/placement", BATCH));
        for (var request : new MockHttpServletRequestBuilder[] {extract, accept, placement}) {
            assertThat(as("ba-on-other-app", request)).isEqualTo(403);
            assertThat(as("ba-on-app", request)).isNotEqualTo(403);
        }
    }

    @Test
    void VYB0906_AC6_aGrantOnTheProductAboveTheApplicationCounts() throws Exception {
        UUID onProduct = ids.get("ba-on-product");
        when(grants.hasEffectiveRole(eq(onProduct), eq(AccessRole.BUSINESS_ANALYST), eq(ScopeType.APP), eq(APP))).thenReturn(true);
        assertThat(as("ba-on-product", json(post("/api/v1/import/candidates/{id}/select", CANDIDATE)))).isNotEqualTo(403);
    }

    @Test
    void VYB0906_AC6_anUnknownCandidateOrBatchIsA404() throws Exception {
        assertThat(as("ba-on-app", json(post("/api/v1/import/candidates/{id}/select", UUID.randomUUID())))).isEqualTo(404);
        assertThat(as("ba-on-app", json(post("/api/v1/import/batches/{id}/extract", UUID.randomUUID())))).isEqualTo(404);
        assertThat(as("ba-on-app", json(post("/api/v1/import/analysis/{id}/dismiss", UUID.randomUUID())))).isEqualTo(404);
    }

    @Test
    void VYB0906_AC6_uploadingChecksTheApplicationNamedInTheRequest() throws Exception {
        // a real file part: the scoped check runs in the handler, after Spring has bound the arguments
        var file = new org.springframework.mock.web.MockMultipartFile("file", "r.csv", "text/csv", "a,b".getBytes());
        var intoOther = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/import").file(file)
            .param("applicationId", OTHER_APP.toString()).param("kind", "PRD_TEMPLATE");
        var intoOwn = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/import").file(file)
            .param("applicationId", APP.toString()).param("kind", "PRD_TEMPLATE");
        assertThat(as("ba-on-app", intoOther)).isEqualTo(403);
        assertThat(as("ba-on-app", intoOwn)).isNotEqualTo(403);
    }
}
