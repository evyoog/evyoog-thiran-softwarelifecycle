package com.vyoog.api.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.assertj.core.api.Assertions.assertThat;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.brief.Brief;
import com.vyoog.brief.BriefPushService;
import com.vyoog.brief.BriefService;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.AppUser;
import com.vyoog.identity.GrantResolver;
import com.vyoog.identity.ScopeType;
import com.vyoog.identity.ServiceAccount;
import com.vyoog.identity.ServiceAccountChecker;
import com.vyoog.identity.ServiceAccountRepository;
import com.vyoog.identity.StepUpChecker;
import com.vyoog.identity.TeamService;
import com.vyoog.identity.UserProvisioningService;
import com.vyoog.importqueue.ImportBatch;
import com.vyoog.importqueue.ImportService;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import com.vyoog.requirements.RequirementService;
import com.vyoog.requirements.RequirementStatus;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * VYB-0902 (F02): the seven riskiest write endpoints each refuse an ordinary signed-in person
 * with a real 403 (through {@link ApiExceptionHandler}) and accept the permitted role.
 *
 * <p>Minimum roles, from the spec's §4.4 matrix:
 * <ul>
 *   <li>Requirement PATCH and DELETE, at the requirement's scope: BUSINESS_ANALYST or ARCHITECT ("Edit req").</li>
 *   <li>Import commit and delete, at the batch's application: BUSINESS_ANALYST or ARCHITECT ("Create req").</li>
 *   <li>Team role change and member removal: ADMINISTRATOR or a LEAD of that team.</li>
 *   <li>Brief push, at the brief's application: APPROVER (the matrix's "Baseline" column).</li>
 *   <li>ADMINISTRATOR passes all of them; service accounts and email-less tokens pass none.</li>
 * </ul>
 */
class Vyb0902WriteGuardsTest {

    private static final UUID CAP = UUID.randomUUID();
    private static final UUID OTHER_CAP = UUID.randomUUID();
    private static final UUID APP = UUID.randomUUID();
    private static final UUID REQ = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();
    private static final UUID BRIEF = UUID.randomUUID();
    private static final UUID TEAM = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();

    private final GrantResolver grants = mock(GrantResolver.class);
    private final UserProvisioningService provisioning = mock(UserProvisioningService.class);
    private final ServiceAccountRepository accounts = mock(ServiceAccountRepository.class);
    private final PrincipalGuard guard = new PrincipalGuard(
        new ServiceAccountChecker(accounts), mock(StepUpChecker.class), provisioning, grants);

    private final RequirementRepository requirements = mock(RequirementRepository.class);
    private final RequirementService requirementService = mock(RequirementService.class);
    private final ImportService importService = mock(ImportService.class);
    private final TeamService teams = mock(TeamService.class);
    private final BriefService briefService = mock(BriefService.class);
    private final BriefPushService briefPush = mock(BriefPushService.class);

    private final Map<String, UUID> userIds = new HashMap<>();
    private final MockMvc mvc;

    Vyb0902WriteGuardsTest() {
        Requirement r = mock(Requirement.class);
        when(r.getId()).thenReturn(REQ);
        when(r.getCapabilityId()).thenReturn(CAP);
        when(r.getStatus()).thenReturn(RequirementStatus.DRAFT);
        when(requirements.findById(REQ)).thenReturn(Optional.of(r));
        when(requirementService.update(any(), anyInt(), any(), any(), any(), any(), any(), any())).thenReturn(r);

        ImportBatch batch = mock(ImportBatch.class);
        when(batch.getApplicationId()).thenReturn(APP);
        when(importService.getBatch(BATCH)).thenReturn(batch);
        when(importService.commit(eq(BATCH), any())).thenReturn(List.of());

        Brief brief = mock(Brief.class);
        when(brief.getApplicationId()).thenReturn(APP);
        when(briefService.get(BRIEF)).thenReturn(brief);
        when(briefPush.push(eq(BRIEF), any())).thenReturn(new BriefPushService.PushResult(true, 200, null));

        // people: id per subject, and which roles each holds where
        person("ordinary");
        person("ba").grant(AccessRole.BUSINESS_ANALYST, ScopeType.CAPABILITY, CAP).grant(AccessRole.BUSINESS_ANALYST, ScopeType.APP, APP);
        person("ba-elsewhere").grant(AccessRole.BUSINESS_ANALYST, ScopeType.CAPABILITY, OTHER_CAP)
            .grant(AccessRole.BUSINESS_ANALYST, ScopeType.APP, UUID.randomUUID());
        person("architect").grant(AccessRole.ARCHITECT, ScopeType.CAPABILITY, CAP).grant(AccessRole.ARCHITECT, ScopeType.APP, APP);
        person("approver").grant(AccessRole.APPROVER, ScopeType.APP, APP).grant(AccessRole.APPROVER, ScopeType.CAPABILITY, CAP);
        person("viewer").grant(AccessRole.VIEWER, ScopeType.PLATFORM, null);
        UUID admin = person("admin").id();
        when(grants.isPlatformAdministrator(admin)).thenReturn(true);
        UUID lead = person("lead").id();
        when(teams.roleOf(TEAM, lead)).thenReturn("LEAD");
        UUID member = person("member").id();
        when(teams.roleOf(TEAM, member)).thenReturn("MEMBER");
        UUID otherLead = person("lead-of-another-team").id();
        when(teams.roleOf(TEAM, otherLead)).thenReturn(null);

        when(accounts.findByClientId("ci-bot"))
            .thenReturn(Optional.of(new ServiceAccount("ci", "t", "ci-bot", List.of("ci:ingest"))));

        mvc = MockMvcBuilders.standaloneSetup(
                build(RequirementController.class), build(ImportController.class),
                build(TeamController.class), build(BriefController.class))
            .setControllerAdvice(new ApiExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();
    }

    private static int anyInt() { return org.mockito.ArgumentMatchers.anyInt(); }

    private record Person(String sub, UUID id, GrantResolver grants) {
        Person grant(AccessRole role, ScopeType type, UUID scopeId) {
            when(grants.hasEffectiveRole(id, role, type, scopeId)).thenReturn(true);
            return this;
        }
    }

    private Person person(String sub) {
        UUID id = UUID.randomUUID();
        userIds.put(sub, id);
        AppUser u = mock(AppUser.class);
        when(u.getId()).thenReturn(id);
        when(provisioning.upsert(eq("sub-" + sub), any(), any())).thenReturn(u);
        return new Person(sub, id, grants);
    }

    /** Builds a controller, giving each constructor parameter a mock unless it is one we hold a real/shared instance of. */
    private <T> T build(Class<T> type) {
        try {
            Constructor<?> ctor = type.getConstructors()[0];
            List<Object> args = new ArrayList<>();
            for (Class<?> p : ctor.getParameterTypes()) {
                if (p == PrincipalGuard.class) args.add(guard);
                else if (p == UserProvisioningService.class) args.add(provisioning);
                else if (p == RequirementRepository.class) args.add(requirements);
                else if (p == RequirementService.class) args.add(requirementService);
                else if (p == ImportService.class) args.add(importService);
                else if (p == TeamService.class) args.add(teams);
                else if (p == BriefService.class) args.add(briefService);
                else if (p == BriefPushService.class) args.add(briefPush);
                else args.add(mock(p));
            }
            return type.cast(ctor.newInstance(args.toArray()));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Jwt jwt(String sub, String email) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject("sub-" + sub)
            .claim("azp", "vyoog-web").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (email != null) b.claim("email", email);
        return b.build();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private int as(String sub, MockHttpServletRequestBuilder request) throws Exception {
        Jwt token = jwt(sub, sub + "@example.com");
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private int asServiceAccount(MockHttpServletRequestBuilder request) throws Exception {
        Jwt token = Jwt.withTokenValue("t").header("alg", "none").subject("sub-ci").claim("azp", "ci-bot")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    // the seven requests -------------------------------------------------------------------

    private MockHttpServletRequestBuilder patchRequirement() {
        return patch("/api/v1/requirements/{id}", REQ).contentType(MediaType.APPLICATION_JSON)
            .content("{\"revision\":1,\"title\":\"t\",\"statement\":\"s\"}");
    }
    private MockHttpServletRequestBuilder deleteRequirement() { return delete("/api/v1/requirements/{id}", REQ); }
    private MockHttpServletRequestBuilder commitImport() { return post("/api/v1/import/batches/{id}/commit", BATCH); }
    private MockHttpServletRequestBuilder deleteBatch() { return delete("/api/v1/import/batches/{id}", BATCH); }
    private MockHttpServletRequestBuilder setTeamRole() {
        return put("/api/v1/teams/{t}/members/{u}/role", TEAM, MEMBER).contentType(MediaType.APPLICATION_JSON)
            .content("{\"role\":\"MEMBER\"}");
    }
    private MockHttpServletRequestBuilder addMember() { return put("/api/v1/teams/{t}/members/{u}", TEAM, MEMBER); }
    private MockHttpServletRequestBuilder removeMember() { return delete("/api/v1/teams/{t}/members/{u}", TEAM, MEMBER); }
    private MockHttpServletRequestBuilder pushBrief() { return post("/api/v1/briefs/{id}/push", BRIEF); }

    // requirement PATCH / DELETE -------------------------------------------------------------

    @Test
    void VYB0902_AC1_patchRequirement_ordinaryUserGets403_editorsAndAdminSucceed() throws Exception {
        for (String no : new String[] {"ordinary", "viewer", "approver", "ba-elsewhere"}) {
            assertThat(as(no, patchRequirement())).as(no).isEqualTo(403);
        }
        assertThat(asServiceAccount(patchRequirement())).isEqualTo(403);
        verify(requirementService, never()).update(any(), anyInt(), any(), any(), any(), any(), any(), any());
        for (String yes : new String[] {"ba", "architect", "admin"}) {
            assertThat(as(yes, patchRequirement())).as(yes).isEqualTo(200);
        }
    }

    @Test
    void VYB0902_AC1_deleteRequirement_ordinaryUserGets403_editorsAndAdminSucceed() throws Exception {
        for (String no : new String[] {"ordinary", "viewer", "approver", "ba-elsewhere"}) {
            assertThat(as(no, deleteRequirement())).as(no).isEqualTo(403);
        }
        assertThat(asServiceAccount(deleteRequirement())).isEqualTo(403);
        verify(requirementService, never()).delete(any(), any(), any());
        for (String yes : new String[] {"ba", "architect", "admin"}) {
            assertThat(as(yes, deleteRequirement())).as(yes).isEqualTo(204);
        }
    }

    @Test
    void VYB0902_AC1_aRequirementThatDoesNotExistIsA404NotAnOpenDoor() throws Exception {
        UUID missing = UUID.randomUUID();
        when(requirements.findById(missing)).thenReturn(Optional.empty());
        assertThat(as("ordinary", delete("/api/v1/requirements/{id}", missing))).isEqualTo(404);
    }

    // import commit / delete -----------------------------------------------------------------

    @Test
    void VYB0902_AC1_importCommit_ordinaryUserGets403_creatorsAndAdminSucceed() throws Exception {
        for (String no : new String[] {"ordinary", "viewer", "approver", "ba-elsewhere"}) {
            assertThat(as(no, commitImport())).as(no).isEqualTo(403);
        }
        assertThat(asServiceAccount(commitImport())).isEqualTo(403);
        verify(importService, never()).commit(any(), any());
        for (String yes : new String[] {"ba", "architect", "admin"}) {
            assertThat(as(yes, commitImport())).as(yes).isEqualTo(200);
        }
    }

    @Test
    void VYB0902_AC1_importDelete_ordinaryUserGets403_creatorsAndAdminSucceed() throws Exception {
        for (String no : new String[] {"ordinary", "viewer", "approver", "ba-elsewhere"}) {
            assertThat(as(no, deleteBatch())).as(no).isEqualTo(403);
        }
        assertThat(asServiceAccount(deleteBatch())).isEqualTo(403);
        verify(importService, never()).deleteBatch(any(), any());
        for (String yes : new String[] {"ba", "architect", "admin"}) {
            assertThat(as(yes, deleteBatch())).as(yes).isEqualTo(204);
        }
    }

    // team role change / member removal ------------------------------------------------------

    @Test
    void VYB0902_AC1_teamRoleChange_ordinaryUserGets403_teamLeadAndAdminSucceed() throws Exception {
        for (String no : new String[] {"ordinary", "member", "lead-of-another-team", "ba", "approver"}) {
            assertThat(as(no, setTeamRole())).as(no).isEqualTo(403);
        }
        assertThat(asServiceAccount(setTeamRole())).isEqualTo(403);
        verify(teams, never()).setRole(any(), any(), any());
        for (String yes : new String[] {"lead", "admin"}) {
            assertThat(as(yes, setTeamRole())).as(yes).isEqualTo(200);
        }
    }

    @Test
    void VYB0902_AC1_teamMemberRemoval_ordinaryUserGets403_teamLeadAndAdminSucceed() throws Exception {
        for (String no : new String[] {"ordinary", "member", "lead-of-another-team", "ba", "approver"}) {
            assertThat(as(no, removeMember())).as(no).isEqualTo(403);
        }
        assertThat(asServiceAccount(removeMember())).isEqualTo(403);
        verify(teams, never()).removeMember(any(), any());
        for (String yes : new String[] {"lead", "admin"}) {
            assertThat(as(yes, removeMember())).as(yes).isEqualTo(200);
        }
    }

    @Test
    void VYB0906_AC7_addingATeamMember_ordinaryUserGets403_teamLeadAndAdminSucceed() throws Exception {
        for (String no : new String[] {"ordinary", "member", "lead-of-another-team", "ba", "approver"}) {
            assertThat(as(no, addMember())).as(no).isEqualTo(403);
        }
        assertThat(asServiceAccount(addMember())).isEqualTo(403);
        verify(teams, never()).addMember(any(), any());
        for (String yes : new String[] {"lead", "admin"}) {
            assertThat(as(yes, addMember())).as(yes).isEqualTo(200);
        }
    }

    // brief push -----------------------------------------------------------------------------

    @Test
    void VYB0902_AC1_briefPush_ordinaryUserGets403_approverAndAdminSucceed() throws Exception {
        for (String no : new String[] {"ordinary", "viewer", "ba", "architect"}) {
            assertThat(as(no, pushBrief())).as(no).isEqualTo(403);
        }
        assertThat(asServiceAccount(pushBrief())).isEqualTo(403);
        verify(briefPush, never()).push(any(), any());
        for (String yes : new String[] {"approver", "admin"}) {
            assertThat(as(yes, pushBrief())).as(yes).isEqualTo(200);
        }
    }

    @Test
    void VYB0902_AC2_theRefusalNamesWhatIsNeeded() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt("ordinary", "ordinary@example.com")));
        String body = mvc.perform(pushBrief()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("APPROVER").contains("ADMINISTRATOR");
    }
}
