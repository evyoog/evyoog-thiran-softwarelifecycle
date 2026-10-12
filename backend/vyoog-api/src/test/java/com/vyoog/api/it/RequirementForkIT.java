package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.changerequest.ChangeRequest;
import com.vyoog.changerequest.ChangeRequestService;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.ScopeType;
import com.vyoog.release.ReleaseService;
import com.vyoog.requirements.AcceptanceCriterionService;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import com.vyoog.requirements.RequirementStatus;
import com.vyoog.trace.TraceGraphService;
import com.vyoog.trace.TraceLinkType;
import com.vyoog.trace.TraceObjectType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0942 (F18, D35): a new version of an approved requirement, against a real PostgreSQL. A fork is a new requirement (new key,
 * DRAFT) copied from the approved one and linked to it and to its approved change request; approving it supersedes the original.
 */
@AutoConfigureMockMvc
class RequirementForkIT extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired ChangeRequestService changeRequests;
    @Autowired AcceptanceCriterionService criteria;
    @Autowired TraceGraphService traceGraph;
    @Autowired ReleaseService releases;
    @Autowired RequirementRepository requirementRows;
    private static final ObjectMapper JSON = new ObjectMapper();

    private JwtRequestPostProcessor token(String id) {
        return jwt().jwt(j -> j.subject("sub-" + id).claim("email", id + "@it.test").claim("preferred_username", id).claim("azp", "vyoog-web"));
    }

    private JwtRequestPostProcessor anAdministrator() {
        String id = unique("adm");
        UUID user = users.upsert("sub-" + id, id + "@it.test", id).getId();
        grants.grant(user, AccessRole.ADMINISTRATOR, ScopeType.PLATFORM, null, null, user);
        return token(id);
    }

    private JwtRequestPostProcessor anOrdinaryUser() {
        String id = unique("ord");
        users.upsert("sub-" + id, id + "@it.test", id);
        return token(id);
    }

    /** An approved requirement with two criteria, and an approved change request that names it. */
    private record Setup(Portfolio p, UUID author, UUID admin, Requirement original, ChangeRequest cr) {}

    private Setup anApprovedRequirementWithAnApprovedChangeRequest() {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        UUID admin = newAdministrator();
        Requirement original = approved(newRequirement(p, author), author, admin);
        criteria.add(original.getId(), "It answers within 2 seconds.");
        criteria.add(original.getId(), "It logs every call.");
        ChangeRequest cr = changeRequests.raise("Tighten the response time", "Customers complain", List.of(original.getId()), author);
        changeRequests.decide(cr.getId(), true, admin);
        return new Setup(p, author, admin, original, cr);
    }

    private String body(UUID changeRequestId) {
        return "{\"changeRequestId\":\"" + changeRequestId + "\"}";
    }

    private UUID fork(Setup s) throws Exception {
        var result = mvc.perform(post("/api/v1/requirements/" + s.original().getId() + "/fork").with(anAdministrator())
                .contentType(MediaType.APPLICATION_JSON).content(body(s.cr().getId()))).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private Requirement reload(UUID id) {
        return requirementRows.findById(id).orElseThrow();
    }

    /** The person who forked it is its author (only they may submit it for review). */
    private UUID authorOf(UUID requirementId) {
        return reload(requirementId).getCreatedBy();
    }

    private void rejected(UUID forkId, Setup s) {
        requirementService.transition(forkId, 1, RequirementStatus.IN_REVIEW, "submitted on purpose, no more criteria", authorOf(forkId));
        requirementService.transition(forkId, 1, RequirementStatus.REVIEWED, null, s.admin());
        requirementService.transition(forkId, 1, RequirementStatus.REJECTED, "Not what we meant", s.admin());
    }

    @Test
    void VYB0942_AC1_aForkIsANewDraftRequirementCopiedFromTheApprovedOneAndTheOriginalIsUntouched() throws Exception {
        Setup s = anApprovedRequirementWithAnApprovedChangeRequest();
        Requirement o = s.original();

        var result = mvc.perform(post("/api/v1/requirements/" + o.getId() + "/fork").with(anAdministrator())
                .contentType(MediaType.APPLICATION_JSON).content(body(s.cr().getId())))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.revision").value(1))
            .andExpect(jsonPath("$.title").value(o.getTitle())).andExpect(jsonPath("$.statement").value(o.getStatement()))
            .andExpect(jsonPath("$.type").value(o.getType())).andExpect(jsonPath("$.priority").value(o.getPriority()))
            .andExpect(jsonPath("$.capabilityId").value(s.p().capabilityId().toString())).andExpect(jsonPath("$.criteriaCount").value(2))
            .andReturn();
        var json = JSON.readTree(result.getResponse().getContentAsString());
        UUID forkId = UUID.fromString(json.get("id").asText());

        assertThat(json.get("key").asText()).as("a new key").isNotEqualTo(o.getKey());
        assertThat(jdbc.queryForObject("SELECT forked_from_id FROM requirement WHERE id = ?", UUID.class, forkId)).isEqualTo(o.getId());
        assertThat(jdbc.queryForObject("SELECT forked_for_change_request_id FROM requirement WHERE id = ?", UUID.class, forkId)).isEqualTo(s.cr().getId());
        assertThat(jdbc.queryForList("SELECT text FROM acceptance_criterion WHERE requirement_id = ? ORDER BY ordinal", String.class, forkId))
            .containsExactly("It answers within 2 seconds.", "It logs every call.");
        Requirement after = reload(o.getId());
        assertThat(after.getStatus()).isEqualTo(RequirementStatus.APPROVED);
        assertThat(after.getRevision()).isEqualTo(o.getRevision());
        assertThat(after.getSupersededById()).as("not replaced until the new version is approved").isNull();
        assertThat(auditCount(forkId, "requirement.forked")).isEqualTo(1);
        assertThat(auditCount(o.getId(), "requirement.fork-opened")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT after->>'changeRequestKey' FROM audit_event WHERE object_id = ? AND action = 'requirement.forked'", String.class, forkId))
            .isEqualTo(s.cr().getKey());
    }

    @Test
    void VYB0942_AC2_upstreamLinksAreCopiedAndTestsDesignAndCodeStayOnTheOriginal() throws Exception {
        Setup s = anApprovedRequirementWithAnApprovedChangeRequest();
        Requirement parent = newRequirement(s.p(), s.author());
        Requirement other = newRequirement(s.p(), s.author());
        traceGraph.createLink(TraceObjectType.REQUIREMENT, parent.getId(), TraceObjectType.REQUIREMENT, s.original().getId(), TraceLinkType.DERIVES, s.author());
        traceGraph.createLink(TraceObjectType.REQUIREMENT, other.getId(), TraceObjectType.REQUIREMENT, s.original().getId(), TraceLinkType.CONFLICTS, s.author());
        UUID testCase = jdbc.queryForObject("INSERT INTO test_case (key, title) VALUES (?, 'A test') RETURNING id", UUID.class, unique("TC"));
        traceGraph.createLink(TraceObjectType.TEST, testCase, TraceObjectType.REQUIREMENT, s.original().getId(), TraceLinkType.VERIFIES, s.author());

        UUID forkId = fork(s);

        var incoming = traceGraph.linksFor(TraceObjectType.REQUIREMENT, forkId).incoming();
        assertThat(incoming).extracting(l -> l.getFromId() + ":" + l.getLinkType()).containsExactly(parent.getId() + ":" + TraceLinkType.DERIVES);
        assertThat(traceGraph.linksFor(TraceObjectType.REQUIREMENT, s.original().getId()).incoming()).as("the original keeps all three").hasSize(3);
        assertThat(jdbc.queryForObject("SELECT (after->>'upstreamLinksCopied')::int FROM audit_event WHERE object_id = ? AND action = 'requirement.forked'", Integer.class, forkId)).isEqualTo(1);
    }

    @Test
    void VYB0942_AC3_aForkIsRefusedUnlessTheOriginalIsApprovedAndTheChangeRequestIsApprovedAndNamesIt() throws Exception {
        Setup s = anApprovedRequirementWithAnApprovedChangeRequest();
        Requirement draft = newRequirement(s.p(), s.author());
        ChangeRequest open = changeRequests.raise("Not decided", "x", List.of(s.original().getId()), s.author());
        ChangeRequest elsewhere = changeRequests.raise("Other scope", "x", List.of(draft.getId()), s.author());
        changeRequests.decide(elsewhere.getId(), true, s.admin());
        JwtRequestPostProcessor admin = anAdministrator();

        mvc.perform(post("/api/v1/requirements/" + draft.getId() + "/fork").with(admin).contentType(MediaType.APPLICATION_JSON).content(body(elsewhere.getId())))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Only an approved requirement")));
        mvc.perform(post("/api/v1/requirements/" + s.original().getId() + "/fork").with(admin).contentType(MediaType.APPLICATION_JSON).content(body(open.getId())))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not approved")));
        mvc.perform(post("/api/v1/requirements/" + s.original().getId() + "/fork").with(admin).contentType(MediaType.APPLICATION_JSON).content(body(elsewhere.getId())))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not in the scope")));
        mvc.perform(post("/api/v1/requirements/" + s.original().getId() + "/fork").with(admin).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM requirement WHERE forked_from_id IN (?, ?)", Integer.class, s.original().getId(), draft.getId())).isZero();
    }

    @Test
    void VYB0942_AC4_onlyOneNewVersionIsOpenAtATimeAndARejectedOrDeletedOneFreesTheOriginal() throws Exception {
        Setup s = anApprovedRequirementWithAnApprovedChangeRequest();
        UUID first = fork(s);

        mvc.perform(post("/api/v1/requirements/" + s.original().getId() + "/fork").with(anAdministrator()).contentType(MediaType.APPLICATION_JSON).content(body(s.cr().getId())))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("already has a new version in progress")));

        rejected(first, s);
        UUID second = fork(s);
        assertThat(second).isNotEqualTo(first);

        requirementService.delete(second, "changed our minds", s.admin());
        assertThat(fork(s)).as("a deleted one frees it too").isNotIn(first, second);
    }

    @Test
    void VYB0942_AC5_aRejectedNewVersionCannotBeReopenedWhileAnotherIsOpen() throws Exception {
        Setup s = anApprovedRequirementWithAnApprovedChangeRequest();
        UUID first = fork(s);
        rejected(first, s);
        fork(s);

        assertThatThrownBy(() -> requirementService.transition(first, 1, RequirementStatus.NEEDS_REVISION, null, authorOf(first)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("another new version in progress");
    }

    @Test
    void VYB0942_AC6_approvingTheNewVersionSupersedesTheOriginalWhoseStatusStaysApproved() throws Exception {
        Setup s = anApprovedRequirementWithAnApprovedChangeRequest();
        UUID forkId = fork(s);

        Requirement approvedFork = approved(reload(forkId), authorOf(forkId), s.admin());

        Requirement original = reload(s.original().getId());
        assertThat(original.getSupersededById()).isEqualTo(forkId);
        assertThat(original.getStatus()).as("no new status (D17)").isEqualTo(RequirementStatus.APPROVED);
        assertThat(approvedFork.getStatus()).isEqualTo(RequirementStatus.APPROVED);
        assertThat(auditCount(s.original().getId(), "requirement.superseded")).isEqualTo(1);
        JwtRequestPostProcessor reader = anOrdinaryUser();
        mvc.perform(get("/api/v1/requirements/" + s.original().getId() + "/versions").with(reader)).andExpect(status().isOk())
            .andExpect(jsonPath("$.supersededBy.id").value(forkId.toString())).andExpect(jsonPath("$.supersededBy.key").value(approvedFork.getKey()))
            .andExpect(jsonPath("$.forks[0].id").value(forkId.toString())).andExpect(jsonPath("$.forkedFrom").doesNotExist());
        mvc.perform(get("/api/v1/requirements/" + forkId + "/versions").with(reader))
            .andExpect(jsonPath("$.forkedFrom.id").value(s.original().getId().toString())).andExpect(jsonPath("$.forkedFrom.key").value(s.original().getKey()))
            .andExpect(jsonPath("$.changeRequestKey").value(s.cr().getKey())).andExpect(jsonPath("$.supersededBy").doesNotExist());
        mvc.perform(post("/api/v1/requirements/" + s.original().getId() + "/fork").with(anAdministrator()).contentType(MediaType.APPLICATION_JSON).content(body(s.cr().getId())))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("already been replaced by " + approvedFork.getKey())));
    }

    @Test
    void VYB0942_AC7_theNewVersionCannotBeApprovedIfSomethingElseAlreadyReplacedTheOriginal() throws Exception {
        Setup s = anApprovedRequirementWithAnApprovedChangeRequest();
        UUID forkId = fork(s);
        Requirement someoneElse = newRequirement(s.p(), s.author());
        jdbc.update("UPDATE requirement SET superseded_by_id = ? WHERE id = ?", someoneElse.getId(), s.original().getId());
        requirementService.transition(forkId, 1, RequirementStatus.IN_REVIEW, "submitted on purpose, no more criteria", authorOf(forkId));
        requirementService.transition(forkId, 1, RequirementStatus.REVIEWED, null, s.admin());

        assertThatThrownBy(() -> requirementService.transition(forkId, 1, RequirementStatus.APPROVED, null, s.admin()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("already been replaced by another version");
        assertThat(reload(forkId).getStatus()).isEqualTo(RequirementStatus.REVIEWED);
    }

    @Test
    void VYB0942_AC8_aReplacedRequirementTakesNoNewLinksAndIsNotCommittedToAReleaseButItsReplacementIs() throws Exception {
        Setup s = anApprovedRequirementWithAnApprovedChangeRequest();
        UUID forkId = fork(s);
        Requirement approvedFork = approved(reload(forkId), authorOf(forkId), s.admin());
        Requirement parent = newRequirement(s.p(), s.author());
        var release = releases.create(unique("R"));

        assertThatThrownBy(() -> traceGraph.createLink(TraceObjectType.REQUIREMENT, parent.getId(), TraceObjectType.REQUIREMENT, s.original().getId(), TraceLinkType.DERIVES, s.author()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("replaced by " + approvedFork.getKey());
        assertThatThrownBy(() -> traceGraph.createLink(TraceObjectType.REQUIREMENT, s.original().getId(), TraceObjectType.REQUIREMENT, parent.getId(), TraceLinkType.DERIVES, s.author()))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> releases.commit(release.getId(), s.original().getId(), s.admin(), "ship it"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Replaced by a newer version").hasMessageContaining(approvedFork.getKey());
        assertThatThrownBy(() -> releases.commitAll(release.getId(), List.of(s.original().getId(), parent.getId()), s.admin(), "ship it"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Nothing was committed");

        traceGraph.createLink(TraceObjectType.REQUIREMENT, parent.getId(), TraceObjectType.REQUIREMENT, approvedFork.getId(), TraceLinkType.DERIVES, s.author());
        releases.commit(release.getId(), approvedFork.getId(), s.admin(), "ship the new version");
        assertThat(releases.scope(release.getId())).containsExactly(approvedFork.getId());
    }

    @Test
    void VYB0942_AC9_onlySomeoneWhoMayEditTheRequirementCanForkItAndAnAnonymousCallerIsRefused() throws Exception {
        Setup s = anApprovedRequirementWithAnApprovedChangeRequest();

        mvc.perform(post("/api/v1/requirements/" + s.original().getId() + "/fork").with(anOrdinaryUser()).contentType(MediaType.APPLICATION_JSON).content(body(s.cr().getId())))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/requirements/" + s.original().getId() + "/fork").contentType(MediaType.APPLICATION_JSON).content(body(s.cr().getId())))
            .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM requirement WHERE forked_from_id = ?", Integer.class, s.original().getId())).isZero();
    }

    @Test
    void VYB0942_AC10_theChangeRequestListsItsForksAndEditingInPlaceUnderItStillWorks() throws Exception {
        Setup s = anApprovedRequirementWithAnApprovedChangeRequest();
        UUID forkId = fork(s);
        JwtRequestPostProcessor admin = anAdministrator();

        mvc.perform(get("/api/v1/change-requests/" + s.cr().getId() + "/forks").with(admin)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].fork.id").value(forkId.toString()))
            .andExpect(jsonPath("$[0].fork.status").value("DRAFT")).andExpect(jsonPath("$[0].original.id").value(s.original().getId().toString()));

        Requirement another = approved(newRequirement(s.p(), s.author()), s.author(), s.admin());
        ChangeRequest cr2 = changeRequests.raise("Small fix", "typo", List.of(another.getId()), s.author());
        changeRequests.decide(cr2.getId(), true, s.admin());
        mvc.perform(patch("/api/v1/requirements/" + another.getId() + "?changeRequestId=" + cr2.getId()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":" + another.getRevision() + ",\"title\":\"" + another.getTitle() + "\",\"statement\":\"The system shall be fixed in place.\","
                    + "\"type\":\"FUNCTIONAL\",\"priority\":\"MEDIUM\",\"capabilityId\":\"" + s.p().capabilityId() + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(get("/api/v1/change-requests/" + UUID.randomUUID() + "/forks").with(admin)).andExpect(status().isNotFound());
    }

    @Test
    void VYB0942_AC11_theDatabaseRefusesASecondLiveForkAndAForkWithoutAChangeRequest() {
        Setup s = anApprovedRequirementWithAnApprovedChangeRequest();
        Requirement a = newRequirement(s.p(), s.author());
        Requirement b = newRequirement(s.p(), s.author());
        jdbc.update("UPDATE requirement SET forked_from_id = ?, forked_for_change_request_id = ? WHERE id = ?", s.original().getId(), s.cr().getId(), a.getId());

        assertThatThrownBy(() -> jdbc.update("UPDATE requirement SET forked_from_id = ?, forked_for_change_request_id = ? WHERE id = ?",
            s.original().getId(), s.cr().getId(), b.getId())).hasMessageContaining("requirement_one_open_fork_idx");
        assertThatThrownBy(() -> jdbc.update("UPDATE requirement SET forked_from_id = ? WHERE id = ?", s.original().getId(), b.getId()))
            .hasMessageContaining("requirement_fork_has_change_request");
        assertThatThrownBy(() -> jdbc.update("UPDATE requirement SET superseded_by_id = id WHERE id = ?", b.getId()))
            .hasMessageContaining("requirement_superseded_not_self");
    }
}
