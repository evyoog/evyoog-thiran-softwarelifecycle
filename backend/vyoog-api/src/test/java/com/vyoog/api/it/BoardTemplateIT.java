package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vyoog.board.BoardTemplateService;
import com.vyoog.board.BoardTemplateService.Fields;
import com.vyoog.identity.TeamService;
import com.vyoog.requirements.Requirement;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0953 (F42, D42): board templates: what a template holds and refuses, that the default is protected, that the requirement list
 * can be narrowed by application and product (what a template's scope needs), and who may change a template. Against a real
 * PostgreSQL, service calls and HTTP with real tokens.
 */
@AutoConfigureMockMvc
class BoardTemplateIT extends IntegrationTestBase {

    @Autowired BoardTemplateService templates;
    @Autowired TeamService teams;
    @Autowired MockMvc mvc;

    private Portfolio p;
    private UUID admin, lead, member, team, otherTeam;
    private JwtRequestPostProcessor leadJwt, memberJwt, adminJwt, outsiderJwt;
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private JwtRequestPostProcessor tokenOf(UUID user) {
        String sub = jdbc.queryForObject("SELECT subject FROM app_user WHERE id = ?", String.class, user);
        return jwt().jwt(j -> j.subject(sub).claim("email", sub.substring(4) + "@it.test").claim("preferred_username", sub.substring(4)).claim("azp", "vyoog-web"));
    }

    @BeforeEach
    void fixtures() {
        p = newPortfolio();
        admin = newAdministrator();
        lead = newUser("lead");
        member = newUser("member");
        UUID outsider = newUser("outsider");
        team = teams.create(unique("Team")).getId();
        otherTeam = teams.create(unique("Other")).getId();
        teams.addMember(team, lead);
        teams.setRole(team, lead, "LEAD");
        teams.addMember(team, member);
        leadJwt = tokenOf(lead);
        memberJwt = tokenOf(member);
        adminJwt = tokenOf(admin);
        outsiderJwt = tokenOf(outsider);
    }

    @AfterEach
    void removeWhatThisTestMade() {
        jdbc.update("DELETE FROM board_template WHERE team_id IN (?, ?)", team, otherTeam);
        jdbc.update("DELETE FROM team_member WHERE team_id IN (?, ?)", team, otherTeam);
        jdbc.update("DELETE FROM team WHERE id IN (?, ?)", team, otherTeam);
    }

    private Fields fields(String name, List<String> columns) {
        return new Fields(team, name, columns, null, null, null, null, null);
    }

    private UUID template(String name) {
        return templates.create(fields(name + " " + unique("t"), List.of("DRAFT", "IN_REVIEW", "APPROVED")), admin);
    }

    // ------------------------------------------------------------------ the default and the shape

    @Test
    void VYB0953_AC1_theDefaultExistsForEveryoneShowsEveryStatusAndIsListedFirst() {
        var all = templates.list();
        var d = all.get(0);
        assertThat(d.isDefault()).isTrue();
        assertThat(d.teamId()).isNull();
        assertThat(d.name()).isEqualTo("Default");
        assertThat(d.columns()).containsExactly("DRAFT", "IN_REVIEW", "REVIEWED", "NEEDS_REVISION", "APPROVED", "REJECTED");
        assertThat(all.stream().filter(t -> t.isDefault())).hasSize(1);
    }

    @Test
    void VYB0953_AC2_aTemplateKeepsItsColumnsInOrderItsScopeAndFiltersAndIsAudited() {
        UUID id = templates.create(new Fields(team, "  Security board ", List.of("APPROVED", "DRAFT", "REVIEWED"), null, p.applicationId(), null, "SECURITY", "HIGH"), lead);
        var t = templates.get(id);
        assertThat(t.name()).isEqualTo("Security board");
        assertThat(t.columns()).containsExactly("APPROVED", "DRAFT", "REVIEWED");
        assertThat(t.applicationId()).isEqualTo(p.applicationId());
        assertThat(t.applicationName()).contains("app");
        assertThat(t.type()).isEqualTo("SECURITY");
        assertThat(t.priority()).isEqualTo("HIGH");
        assertThat(t.teamName()).isNotBlank();
        assertThat(t.isDefault()).isFalse();
        assertThat(auditCount(id, "board.template_created")).isEqualTo(1);

        templates.update(id, new Fields(null, "Security board", List.of("DRAFT", "APPROVED"), null, null, p.capabilityId(), null, null), lead);
        var u = templates.get(id);
        assertThat(u.columns()).containsExactly("DRAFT", "APPROVED");
        assertThat(u.applicationId()).isNull();
        assertThat(u.capabilityId()).isEqualTo(p.capabilityId());
        assertThat(u.type()).isNull();
        // the chain the scope sits under comes back too, so the edit form can preselect a capability's application and product
        assertThat(u.scopeApplicationId()).isEqualTo(p.applicationId());
        assertThat(u.scopeProductId()).isEqualTo(p.productId());
        assertThat(u.applicationName()).contains("app");
        assertThat(u.productName()).contains("product");
        assertThat(templates.get(id).capabilityName()).contains("cap");
        assertThat(auditCount(id, "board.template_updated")).isEqualTo(1);
    }

    @Test
    void VYB0953_AC3_aTemplateRefusesNoColumnsADuplicateOrUnknownStatusTwoScopesAndABadTypeOrPriority() {
        assertThatThrownBy(() -> templates.create(fields("a", List.of()), admin)).hasMessageContaining("at least one column");
        assertThatThrownBy(() -> templates.create(fields("a", List.of("DRAFT", "DRAFT")), admin)).hasMessageContaining("twice");
        assertThatThrownBy(() -> templates.create(fields("a", List.of("VERIFIED")), admin)).hasMessageContaining("Not a status");
        assertThatThrownBy(() -> templates.create(fields(" ", List.of("DRAFT")), admin)).hasMessageContaining("name");
        assertThatThrownBy(() -> templates.create(fields("x".repeat(101), List.of("DRAFT")), admin)).hasMessageContaining("at most");
        assertThatThrownBy(() -> templates.create(new Fields(team, "two", List.of("DRAFT"), p.productId(), p.applicationId(), null, null, null), admin))
            .hasMessageContaining("one of a product");
        assertThatThrownBy(() -> templates.create(new Fields(team, "gone", List.of("DRAFT"), null, UUID.randomUUID(), null, null, null), admin))
            .hasMessageContaining("No such application");
        assertThatThrownBy(() -> templates.create(new Fields(team, "ty", List.of("DRAFT"), null, null, null, "NONSENSE", null), admin)).hasMessageContaining("type");
        assertThatThrownBy(() -> templates.create(new Fields(team, "pr", List.of("DRAFT"), null, null, null, null, "URGENT"), admin)).hasMessageContaining("priority");
        assertThatThrownBy(() -> templates.create(new Fields(null, "noteam", List.of("DRAFT"), null, null, null, null, null), admin)).hasMessageContaining("team");
    }

    @Test
    void VYB0953_AC4_aNameIsFreePerTeamButNotTwiceInOneAndTheDatabaseAgrees() {
        String n = "Shared " + unique("n");
        templates.create(fields(n, List.of("DRAFT")), admin);
        assertThatThrownBy(() -> templates.create(fields(n.toUpperCase(), List.of("DRAFT")), admin)).hasMessageContaining("already a template");
        templates.create(new Fields(otherTeam, n, List.of("DRAFT"), null, null, null, null, null), admin); // another team may use it
        assertThatThrownBy(() -> jdbc.update("INSERT INTO board_template (team_id, name, columns) VALUES (?, ?, ARRAY['DRAFT'])", team, n))
            .hasMessageContaining("board_template");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO board_template (team_id, name, columns) VALUES (?, 'empty', ARRAY[]::text[])", team))
            .hasMessageContaining("board_template");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO board_template (team_id, name, columns, product_id, application_id) VALUES (?, 'two', ARRAY['DRAFT'], ?, ?)",
            team, p.productId(), p.applicationId())).hasMessageContaining("board_template");
    }

    @Test
    void VYB0953_AC5_theDefaultIsNeverDeletedATeamTemplateIsAndItsGoneAfterwardAndAudited() {
        UUID defaultId = templates.list().get(0).id();
        assertThatThrownBy(() -> templates.delete(defaultId, admin)).hasMessageContaining("default");
        assertThat(templates.get(defaultId).name()).isEqualTo("Default");

        UUID id = template("Temporary");
        templates.delete(id, lead);
        assertThatThrownBy(() -> templates.get(id)).isInstanceOf(NoSuchElementException.class);
        assertThat(auditCount(id, "board.template_deleted")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the list filters a template's scope needs

    @Test
    void VYB0953_AC6_theRequirementListNarrowsByApplicationAndProductAsAColumnNeeds() throws Exception {
        Portfolio other = newPortfolio();
        UUID author = newUser("author");
        Requirement mine = newRequirement(p, author);
        Requirement theirs = newRequirement(other, author);
        String mineId = mine.getId().toString(), theirsId = theirs.getId().toString();

        mvc.perform(get("/api/v1/requirements").with(outsiderJwt).param("applicationId", p.applicationId().toString()).param("status", "DRAFT").param("size", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[?(@.id=='" + mineId + "')]").isNotEmpty())
            .andExpect(jsonPath("$.content[?(@.id=='" + theirsId + "')]").isEmpty());
        mvc.perform(get("/api/v1/requirements").with(outsiderJwt).param("productId", other.productId().toString()).param("size", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[?(@.id=='" + theirsId + "')]").isNotEmpty())
            .andExpect(jsonPath("$.content[?(@.id=='" + mineId + "')]").isEmpty());
        mvc.perform(get("/api/v1/requirements").with(outsiderJwt).param("capabilityId", p.capabilityId().toString()).param("status", "APPROVED").param("size", "100"))
            .andExpect(jsonPath("$.content[?(@.id=='" + mineId + "')]").isEmpty());
    }

    // ------------------------------------------------------------------ who may change a template, over HTTP

    private String body(String name) {
        return "{\"name\":\"" + name + "\",\"columns\":[\"DRAFT\",\"APPROVED\"]}";
    }

    @Test
    void VYB0953_AC7_aTeamsLeadOrAnAdministratorMakesAndChangesItsTemplatesNobodyElseAndAnyoneSignedInReads() throws Exception {
        String create = "{\"teamId\":\"%s\",\"name\":\"%s\",\"columns\":[\"DRAFT\",\"REVIEWED\"]}";

        for (JwtRequestPostProcessor denied : List.of(memberJwt, outsiderJwt)) {
            mvc.perform(post("/api/v1/boards/templates").with(denied).contentType(JSON).content(String.format(create, team, "Nope"))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/boards/templates").contentType(JSON).content(String.format(create, team, "Nope"))).andExpect(status().isUnauthorized());
        // a lead of one team does not lead another
        mvc.perform(post("/api/v1/boards/templates").with(leadJwt).contentType(JSON).content(String.format(create, otherTeam, "Nope"))).andExpect(status().isForbidden());

        String made = mvc.perform(post("/api/v1/boards/templates").with(leadJwt).contentType(JSON).content(String.format(create, team, "Lead's board")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNotEmpty()).andReturn().getResponse().getContentAsString();
        String id = made.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(post("/api/v1/boards/templates").with(adminJwt).contentType(JSON).content(String.format(create, otherTeam, "Admin's board"))).andExpect(status().isCreated());

        for (JwtRequestPostProcessor denied : List.of(memberJwt, outsiderJwt)) {
            mvc.perform(put("/api/v1/boards/templates/" + id).with(denied).contentType(JSON).content(body("Changed"))).andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/boards/templates/" + id).with(denied)).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/boards/templates").with(denied)).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id=='" + id + "')]").isNotEmpty());
        }
        assertThat(templates.get(UUID.fromString(id)).name()).isEqualTo("Lead's board");
        mvc.perform(put("/api/v1/boards/templates/" + id).with(leadJwt).contentType(JSON).content(body("Changed"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Changed")).andExpect(jsonPath("$.columns[1]").value("APPROVED"));
        mvc.perform(delete("/api/v1/boards/templates/" + id).with(leadJwt)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/boards/templates")).andExpect(status().isUnauthorized());
    }

    @Test
    void VYB0953_AC8_onlyAnAdministratorChangesTheDefaultAndNobodyDeletesIt() throws Exception {
        String defaultId = templates.list().get(0).id().toString();
        try {
            mvc.perform(put("/api/v1/boards/templates/" + defaultId).with(leadJwt).contentType(JSON).content(body("Default"))).andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/boards/templates/" + defaultId).with(leadJwt)).andExpect(status().isForbidden());
            assertThat(templates.get(UUID.fromString(defaultId)).columns()).hasSize(6);

            mvc.perform(delete("/api/v1/boards/templates/" + defaultId).with(adminJwt)).andExpect(status().isConflict());
            mvc.perform(put("/api/v1/boards/templates/" + defaultId).with(adminJwt).contentType(JSON).content(body("Default"))).andExpect(status().isOk());
            assertThat(templates.get(UUID.fromString(defaultId)).columns()).containsExactly("DRAFT", "APPROVED");
        } finally {
            jdbc.update("UPDATE board_template SET columns = ARRAY['DRAFT','IN_REVIEW','REVIEWED','NEEDS_REVISION','APPROVED','REJECTED'] WHERE id = ?::uuid", defaultId);
        }
    }
}
