package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vyoog.changerequest.ChangeRequest;
import com.vyoog.changerequest.ChangeRequestService;
import com.vyoog.defect.Defect;
import com.vyoog.defect.DefectLifecycleService;
import com.vyoog.defect.DefectService;
import com.vyoog.defect.DefectSeverity;
import com.vyoog.defect.FoundIn;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.ScopeType;
import com.vyoog.release.ReleaseService;
import com.vyoog.requirements.Placement;
import com.vyoog.requirements.Requirement;
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
 * VYB-0943 (F19, D36): the trace UI's backend, against a real PostgreSQL. What a requirement is linked to by name, linking an
 * untraced defect to a requirement, the requirement-by-release and requirement-by-defect matrices, and the items behind a
 * change request's impact counts.
 */
@AutoConfigureMockMvc
class TraceUiIT extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired DefectService defects;
    @Autowired DefectLifecycleService lifecycle;
    @Autowired ReleaseService releases;
    @Autowired TraceGraphService trace;
    @Autowired ChangeRequestService changeRequests;

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

    private UUID aTestCase(String title) {
        return jdbc.queryForObject("INSERT INTO test_case (key, title) VALUES (?, ?) RETURNING id", UUID.class, unique("TC"), title);
    }

    private UUID aDesignNode(UUID applicationId, String label) {
        UUID flow = jdbc.queryForObject("INSERT INTO design_flow (application_id) VALUES (?) ON CONFLICT (application_id) DO UPDATE SET application_id = EXCLUDED.application_id RETURNING id",
            UUID.class, applicationId);
        return jdbc.queryForObject("INSERT INTO design_node (flow_id, kind, label) VALUES (?, 'step', ?) RETURNING id", UUID.class, flow, label);
    }

    // ------------------------------------------------------------------ A: linked work and defect linking

    @Test
    void VYB0943_AC1_theLinkedWorkOfARequirementIsNamedTestsDesignReleaseAndDefects() throws Exception {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        Requirement r = newRequirement(p, author);
        UUID test = aTestCase("Export works");
        trace.createLink(TraceObjectType.TEST, test, TraceObjectType.REQUIREMENT, r.getId(), TraceLinkType.VERIFIES, author);
        UUID node = aDesignNode(p.applicationId(), "Export step");
        jdbc.update("INSERT INTO design_node_requirement (node_id, requirement_id) VALUES (?, ?)", node, r.getId());
        var release = releases.create(unique("R"));
        releases.commit(release.getId(), r.getId(), author, "in scope");
        Defect low = defects.raise("Typo in header", DefectSeverity.LOW, r.getId(), FoundIn.QA, author);
        Defect critical = defects.raise("Export is blank", DefectSeverity.CRITICAL, r.getId(), FoundIn.QA, author);

        mvc.perform(get("/api/v1/requirements/" + r.getId() + "/linked").with(anOrdinaryUser())).andExpect(status().isOk())
            .andExpect(jsonPath("$.applicationId").value(p.applicationId().toString()))
            .andExpect(jsonPath("$.tests.length()").value(1)).andExpect(jsonPath("$.tests[0].title").value("Export works"))
            .andExpect(jsonPath("$.designNodes[0].label").value("Export step")).andExpect(jsonPath("$.designNodes[0].kind").value("step"))
            .andExpect(jsonPath("$.releases[0].name").value(release.getName())).andExpect(jsonPath("$.releases[0].state").value("PLANNED"))
            .andExpect(jsonPath("$.defects.length()").value(2)).andExpect(jsonPath("$.defects[0].key").value(critical.getKey()))
            .andExpect(jsonPath("$.defects[1].key").value(low.getKey()));
        mvc.perform(get("/api/v1/requirements/" + UUID.randomUUID() + "/linked").with(anOrdinaryUser())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/requirements/" + r.getId() + "/linked")).andExpect(status().isUnauthorized());
    }

    @Test
    void VYB0943_AC2_aRequirementWithNothingLinkedSaysSoAndAnUnplacedOneHasNoApplication() throws Exception {
        UUID author = newUser("author");
        Requirement unplaced = requirementService.create(unique("T"), "The system shall be lonely.", "FUNCTIONAL", "MEDIUM", Placement.unplaced(), author);

        mvc.perform(get("/api/v1/requirements/" + unplaced.getId() + "/linked").with(anOrdinaryUser())).andExpect(status().isOk())
            .andExpect(jsonPath("$.applicationId").doesNotExist()).andExpect(jsonPath("$.tests.length()").value(0))
            .andExpect(jsonPath("$.designNodes.length()").value(0)).andExpect(jsonPath("$.releases.length()").value(0))
            .andExpect(jsonPath("$.defects.length()").value(0));
    }

    @Test
    void VYB0943_AC3_aDefectWithNoRequirementCanBeLinkedToOneAndTheLinkIsAudited() throws Exception {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        Requirement r = newRequirement(p, author);
        Defect d = defects.raise("Found in the field", DefectSeverity.HIGH, null, FoundIn.PRODUCTION, author);
        assertThat(d.isUntraced()).isTrue();

        mvc.perform(put("/api/v1/defects/" + d.getId() + "/requirement").with(anAdministrator()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirementId\":\"" + r.getId() + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.defect.requirementId").value(r.getId().toString()))
            .andExpect(jsonPath("$.defect.untraced").value(false));

        assertThat(jdbc.queryForObject("SELECT requirement_id FROM defect WHERE id = ?", UUID.class, d.getId())).isEqualTo(r.getId());
        assertThat(auditCount(d.getId(), "defect.requirement-linked")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT after->>'requirementKey' FROM audit_event WHERE object_id = ? AND action = 'defect.requirement-linked'", String.class, d.getId()))
            .isEqualTo(r.getKey());
    }

    @Test
    void VYB0943_AC4_aDefectThatAlreadyHasARequirementIsNeverMovedByALink() throws Exception {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        Requirement first = newRequirement(p, author);
        Requirement other = newRequirement(p, author);
        Defect d = defects.raise("Already traced", DefectSeverity.MEDIUM, first.getId(), FoundIn.QA, author);

        mvc.perform(put("/api/v1/defects/" + d.getId() + "/requirement").with(anAdministrator()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirementId\":\"" + other.getId() + "\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("already linked to " + first.getKey())));

        assertThat(jdbc.queryForObject("SELECT requirement_id FROM defect WHERE id = ?", UUID.class, d.getId())).isEqualTo(first.getId());
        assertThat(auditCount(d.getId(), "defect.requirement-linked")).isZero();
    }

    @Test
    void VYB0943_AC5_aDefectIsNotLinkedToAMissingOrReplacedRequirementAndTheRequestMustNameOne() throws Exception {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        Requirement replaced = newRequirement(p, author);
        Requirement newer = newRequirement(p, author);
        jdbc.update("UPDATE requirement SET superseded_by_id = ? WHERE id = ?", newer.getId(), replaced.getId());
        Defect d = defects.raise("Orphan", DefectSeverity.LOW, null, FoundIn.QA, author);
        JwtRequestPostProcessor admin = anAdministrator();

        mvc.perform(put("/api/v1/defects/" + d.getId() + "/requirement").with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirementId\":\"" + UUID.randomUUID() + "\"}")).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/defects/" + d.getId() + "/requirement").with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirementId\":\"" + replaced.getId() + "\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("replaced by " + newer.getKey())));
        mvc.perform(put("/api/v1/defects/" + d.getId() + "/requirement").with(admin).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT requirement_id FROM defect WHERE id = ?", UUID.class, d.getId())).isNull();
    }

    @Test
    void VYB0943_AC6_onlySomeoneWithTheQaRoleCanLinkADefectAndAnAnonymousCallerIsRefused() throws Exception {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        Requirement r = newRequirement(p, author);
        Defect d = defects.raise("Needs a home", DefectSeverity.LOW, null, FoundIn.QA, author);

        mvc.perform(put("/api/v1/defects/" + d.getId() + "/requirement").with(anOrdinaryUser()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirementId\":\"" + r.getId() + "\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/defects/" + d.getId() + "/requirement").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirementId\":\"" + r.getId() + "\"}")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT requirement_id FROM defect WHERE id = ?", UUID.class, d.getId())).isNull();
    }

    @Test
    void VYB0943_AC7_theDefectListCanBeNarrowedToTheOnesWithNoRequirement() throws Exception {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        Requirement r = newRequirement(p, author);
        String word = "zz" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Defect traced = defects.raise(word + " traced", DefectSeverity.LOW, r.getId(), FoundIn.QA, author);
        Defect untraced = defects.raise(word + " untraced", DefectSeverity.LOW, null, FoundIn.QA, author);

        mvc.perform(get("/api/v1/defects?q=" + word + "&untraced=true").with(anOrdinaryUser())).andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.content[0].key").value(untraced.getKey()));
        mvc.perform(get("/api/v1/defects?q=" + word).with(anOrdinaryUser()))
            .andExpect(jsonPath("$.content.length()").value(2));
        assertThat(traced.isUntraced()).isFalse();
    }

    // ------------------------------------------------------------------ B: the two matrices

    @Test
    void VYB0943_AC8_theReleaseMatrixHasEveryRequirementOfTheApplicationAndTheReleaseEachIsIn() throws Exception {
        Portfolio p = newPortfolio();
        Portfolio other = newPortfolio();
        UUID author = newUser("author");
        Requirement inRelease = newRequirement(p, author);
        Requirement inNone = newRequirement(p, author);
        Requirement onApplication = requirementService.create(unique("T"), "The system shall sit on the app.", "FUNCTIONAL", "MEDIUM",
            Placement.application(p.applicationId()), author);
        Requirement elsewhere = newRequirement(other, author);
        Requirement deleted = newRequirement(p, author);
        requirementService.delete(deleted.getId(), "mistake", author);
        var planned = releases.create(unique("R-planned"));
        var empty = releases.create(unique("R-empty"));
        var done = releases.create(unique("R-done"));
        var doneEmpty = releases.create(unique("R-done-empty"));
        releases.commit(planned.getId(), inRelease.getId(), author, "in scope");
        releases.commit(done.getId(), onApplication.getId(), author, "shipped");
        jdbc.update("UPDATE release SET state = 'RELEASED' WHERE id IN (?, ?)", done.getId(), doneEmpty.getId());
        releases.commit(planned.getId(), elsewhere.getId(), author, "another app's");

        var result = mvc.perform(get("/api/v1/trace/release-matrix/" + p.applicationId()).with(anOrdinaryUser())).andExpect(status().isOk()).andReturn();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());

        assertThat(json.get("rows")).extracting(n -> n.get("key").asText())
            .containsExactlyInAnyOrder(inRelease.getKey(), inNone.getKey(), onApplication.getKey());
        for (var row : json.get("rows")) {
            String key = row.get("key").asText();
            String releaseId = row.get("releaseId") == null || row.get("releaseId").isNull() ? null : row.get("releaseId").asText();
            if (key.equals(inRelease.getKey())) assertThat(releaseId).isEqualTo(planned.getId().toString());
            else if (key.equals(onApplication.getKey())) assertThat(releaseId).isEqualTo(done.getId().toString());
            else assertThat(releaseId).as("in no release").isNull();
        }
        assertThat(json.get("releases")).extracting(n -> n.get("name").asText())
            .contains(planned.getName(), empty.getName(), done.getName()).doesNotContain(doneEmpty.getName());
        for (var col : json.get("releases")) {
            if (col.get("name").asText().equals(planned.getName())) assertThat(col.get("requirements").asInt()).as("only this application's").isEqualTo(1);
        }
        mvc.perform(get("/api/v1/trace/release-matrix/" + p.applicationId())).andExpect(status().isUnauthorized());
    }

    @Test
    void VYB0943_AC9_theDefectMatrixShowsOpenDefectsPerRequirementAndTheUntracedOnesApart() throws Exception {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        Requirement withOpen = newRequirement(p, author);
        Requirement withFixed = newRequirement(p, author);
        Requirement clean = newRequirement(p, author);
        Defect open = defects.raise("Open one", DefectSeverity.HIGH, withOpen.getId(), FoundIn.QA, author);
        Defect fixed = defects.raise("Fixed one", DefectSeverity.LOW, withFixed.getId(), FoundIn.QA, author);
        lifecycle.markFixed(fixed.getId(), "patched", author);
        Defect nobody = defects.raise("Belongs to nobody", DefectSeverity.MEDIUM, null, FoundIn.UAT, author);
        Requirement replaced = newRequirement(p, author);
        Requirement newer = newRequirement(p, author);
        jdbc.update("UPDATE requirement SET superseded_by_id = ? WHERE id = ?", newer.getId(), replaced.getId());

        mvc.perform(get("/api/v1/trace/defect-matrix/" + p.applicationId()).with(anOrdinaryUser())).andExpect(status().isOk())
            .andExpect(jsonPath("$.rows.length()").value(5))
            .andExpect(jsonPath("$.defects.length()").value(1)).andExpect(jsonPath("$.defects[0].key").value(open.getKey()))
            .andExpect(jsonPath("$.defects[0].requirementId").value(withOpen.getId().toString()))
            .andExpect(jsonPath("$.defects[0].severity").value("HIGH"))
            .andExpect(jsonPath("$.untraced[?(@.key == '" + nobody.getKey() + "')]").exists());
        mvc.perform(get("/api/v1/trace/defect-matrix/" + p.applicationId() + "?includeClosed=true").with(anOrdinaryUser()))
            .andExpect(jsonPath("$.defects.length()").value(2))
            .andExpect(jsonPath("$.defects[?(@.key == '" + fixed.getKey() + "')].state").value("FIXED"))
            .andExpect(jsonPath("$.rows[?(@.key == '" + replaced.getKey() + "')].supersededById").value(newer.getId().toString()));
        assertThat(clean.getKey()).isNotBlank();
    }

    // ------------------------------------------------------------------ C: impact items

    @Test
    void VYB0943_AC10_theImpactListsNameTheItemsBehindEachCountAndMarkWhatTheChangeRequestNames() throws Exception {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        UUID admin = newAdministrator();
        Requirement named = approved(newRequirement(p, author), author, admin);
        Requirement child = newRequirement(p, author);
        UUID test = aTestCase("Child is checked");
        trace.createLink(TraceObjectType.REQUIREMENT, named.getId(), TraceObjectType.REQUIREMENT, child.getId(), TraceLinkType.DERIVES, author);
        trace.createLink(TraceObjectType.REQUIREMENT, child.getId(), TraceObjectType.TEST, test, TraceLinkType.VERIFIES, author);
        jdbc.update("INSERT INTO brief (application_id, target, content) VALUES (?, 'HUMAN', 'brief text')", p.applicationId());
        UUID brief = jdbc.queryForObject("SELECT id FROM brief WHERE application_id = ? ORDER BY generated_at DESC LIMIT 1", UUID.class, p.applicationId());
        jdbc.update("INSERT INTO brief_requirement (brief_id, requirement_id, revision) VALUES (?, ?, 1)", brief, child.getId());
        ChangeRequest cr = changeRequests.raise("Change the named one", "why", List.of(named.getId()), author);

        mvc.perform(post("/api/v1/change-requests/" + cr.getId() + "/impact").with(anOrdinaryUser())).andExpect(status().isOk())
            .andExpect(jsonPath("$.requirements").value(2)).andExpect(jsonPath("$.tests").value(1))
            .andExpect(jsonPath("$.applications").value(1)).andExpect(jsonPath("$.briefs").value(1))
            .andExpect(jsonPath("$.items.requirements.length()").value(2))
            .andExpect(jsonPath("$.items.requirements[?(@.key == '" + named.getKey() + "')].inScope").value(true))
            .andExpect(jsonPath("$.items.requirements[?(@.key == '" + child.getKey() + "')].inScope").value(false))
            .andExpect(jsonPath("$.items.tests[0].title").value("Child is checked"))
            .andExpect(jsonPath("$.items.applications[0].id").value(p.applicationId().toString()))
            .andExpect(jsonPath("$.items.briefs[0].id").value(brief.toString())).andExpect(jsonPath("$.items.briefs[0].target").value("HUMAN"));
    }

    @Test
    void VYB0943_AC11_everyListIsAsLongAsItsCountAndAnEmptyImpactHasEmptyLists() throws Exception {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        Requirement alone = newRequirement(p, author);
        ChangeRequest cr = changeRequests.raise("Alone", "why", List.of(alone.getId()), author);

        var detail = changeRequests.computeImpactDetail(cr.getId());

        assertThat(detail.items().requirements()).hasSize(detail.summary().requirements());
        assertThat(detail.items().tests()).hasSize(detail.summary().tests());
        assertThat(detail.items().applications()).hasSize(detail.summary().applications());
        assertThat(detail.items().briefs()).hasSize(detail.summary().briefs());
        assertThat(detail.items().tests()).isEmpty();
        assertThat(detail.items().briefs()).isEmpty();
        assertThatThrownBy(() -> changeRequests.computeImpactDetail(UUID.randomUUID())).isInstanceOf(RuntimeException.class);
    }
}
