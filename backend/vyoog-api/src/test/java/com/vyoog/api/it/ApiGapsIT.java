package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.ScopeType;
import com.vyoog.requirements.Requirement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0961 (D49): two gaps the pilot dry run and the load rehearsal found in the API. {@code PATCH /requirements/{id}} left a requirement
 * unplaced when the body did not mention its capability (so a client that forgot the field orphaned it and took the editor's rights
 * with it), and {@code POST /teams} answered with nothing, so a script had to list the teams and match by name. Over HTTP, with real tokens.
 */
@AutoConfigureMockMvc
class ApiGapsIT extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private JwtRequestPostProcessor tokenOf(UUID user) {
        String sub = jdbc.queryForObject("SELECT subject FROM app_user WHERE id = ?", String.class, user);
        return jwt().jwt(j -> j.subject(sub).claim("email", sub.substring(4) + "@it.test").claim("preferred_username", sub.substring(4)).claim("azp", "vyoog-web"));
    }

    private UUID capabilityOf(Requirement r) {
        return jdbc.queryForObject("SELECT capability_id FROM requirement WHERE id = ?", UUID.class, r.getId());
    }

    private int revisionOf(Requirement r) {
        return jdbc.queryForObject("SELECT revision FROM requirement WHERE id = ?", Integer.class, r.getId());
    }

    private String body(Requirement r, String capabilityFragment, String title) {
        return "{\"revision\":" + revisionOf(r) + ",\"title\":\"" + title + "\",\"statement\":\"The system shall keep its place.\",\"type\":\"FUNCTIONAL\",\"priority\":\"MEDIUM\""
            + capabilityFragment + "}";
    }

    @Test
    void VYB0961_AC1_leavingOutTheCapabilityKeepsTheRequirementWhereItIsAndTheEditorCanStillEditIt() throws Exception {
        Portfolio p = newPortfolio();
        UUID analyst = grantOnCapability(newUser("analyst"), AccessRole.BUSINESS_ANALYST, p.capabilityId());
        Requirement r = newRequirement(p, analyst);

        mvc.perform(patch("/api/v1/requirements/" + r.getId()).with(tokenOf(analyst)).contentType(JSON).content(body(r, "", "Edited without a capability")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Edited without a capability"))
            .andExpect(jsonPath("$.capabilityId").value(p.capabilityId().toString()));

        assertThat(capabilityOf(r)).as("not unplaced").isEqualTo(p.capabilityId());
        mvc.perform(patch("/api/v1/requirements/" + r.getId()).with(tokenOf(analyst)).contentType(JSON).content(body(r, "", "Edited again")))
            .andExpect(status().isOk()); // the analyst's rights came from the placement, which is still there
    }

    @Test
    void VYB0961_AC2_anExplicitNullUnplacesAndAValueMovesIt() throws Exception {
        Portfolio p = newPortfolio();
        Portfolio other = newPortfolio();
        UUID admin = newAdministrator();
        Requirement r = newRequirement(p, admin);
        JwtRequestPostProcessor as = tokenOf(admin);

        mvc.perform(patch("/api/v1/requirements/" + r.getId()).with(as).contentType(JSON).content(body(r, ",\"capabilityId\":\"" + other.capabilityId() + "\"", "Moved")))
            .andExpect(status().isOk());
        assertThat(capabilityOf(r)).isEqualTo(other.capabilityId());

        mvc.perform(patch("/api/v1/requirements/" + r.getId()).with(as).contentType(JSON).content(body(r, ",\"capabilityId\":null", "Unplaced")))
            .andExpect(status().isOk());
        assertThat(capabilityOf(r)).as("a null that was sent is a request to unplace").isNull();

        mvc.perform(patch("/api/v1/requirements/" + r.getId()).with(as).contentType(JSON).content(body(r, "", "Still unplaced")))
            .andExpect(status().isOk());
        assertThat(capabilityOf(r)).as("and leaving it out afterwards keeps it unplaced").isNull();
    }

    @Test
    void VYB0961_AC3_aMalformedCapabilityIsStillARefusalNotAnUnplace() throws Exception {
        Portfolio p = newPortfolio();
        UUID admin = newAdministrator();
        Requirement r = newRequirement(p, admin);

        mvc.perform(patch("/api/v1/requirements/" + r.getId()).with(tokenOf(admin)).contentType(JSON).content(body(r, ",\"capabilityId\":\"not-an-id\"", "x")))
            .andExpect(status().isBadRequest());

        assertThat(capabilityOf(r)).isEqualTo(p.capabilityId());
    }

    @Test
    void VYB0961_AC4_creatingATeamAnswersWithTheNewTeamAndItsIdWorks() throws Exception {
        UUID admin = newAdministrator();
        UUID member = newUser("member");
        String name = unique("Team");

        String created = mvc.perform(post("/api/v1/teams").with(tokenOf(admin)).contentType(JSON).content("{\"name\":\"" + name + "\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value(name)).andExpect(jsonPath("$.id").isNotEmpty())
            .andReturn().getResponse().getContentAsString();
        JsonNode team = json.readTree(created);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/teams/" + team.get("id").asText() + "/members/" + member).with(tokenOf(admin)))
            .andExpect(status().is2xxSuccessful());
        mvc.perform(get("/api/v1/teams/" + team.get("id").asText() + "/members").with(tokenOf(admin)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM team WHERE id = ?::uuid AND name = ?", Integer.class, team.get("id").asText(), name)).isEqualTo(1);
    }

    @Test
    void VYB0961_AC5_onlyAnAdministratorCreatesATeam() throws Exception {
        UUID analyst = grant(newUser("analyst"));
        mvc.perform(post("/api/v1/teams").with(tokenOf(analyst)).contentType(JSON).content("{\"name\":\"Nope\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/teams").contentType(JSON).content("{\"name\":\"Nope\"}")).andExpect(status().isUnauthorized());
    }

    private UUID grant(UUID user) {
        Portfolio p = newPortfolio();
        return grantOnCapability(user, AccessRole.BUSINESS_ANALYST, p.capabilityId());
    }
}
