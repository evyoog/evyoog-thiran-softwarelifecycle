package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0959 (F11): the pilot onboarding runbook ({@code docs/08-architecture/deployment/pilot-onboarding.md}) as a script. An invented
 * team is taken from an empty portfolio to its first approved requirements and a release, step by step, through the real HTTP layer
 * with real signed tokens, against a real PostgreSQL. Each step here is one numbered step of the runbook; if the runbook and the
 * product drift apart, this is the test that says so.
 *
 * <p>What it deliberately does not do: the one-time bootstrap of the very first administrator ({@code Vyb0901OpenDoorsTest} owns that;
 * a shared test database already has an administrator, and bootstrap refuses when one exists) and the real sign-in against Keycloak
 * (a signed test token stands in, as in every other integration test; the runbook lists the Keycloak steps as human steps).
 */
@AutoConfigureMockMvc
class PilotDryRunIT extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    /** A token the way Keycloak issues one: a subject nobody chose, the email address and the username the person signs in with. */
    private JwtRequestPostProcessor signedIn(String subject, String email) {
        return jwt().jwt(j -> j.subject(subject).claim("email", email).claim("preferred_username", email.substring(0, email.indexOf('@'))).claim("azp", "vyoog-web"));
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private JsonNode postJson(String url, Object payload, JwtRequestPostProcessor who) throws Exception {
        return body(mvc.perform(post(url).contentType(JSON).content(json.writeValueAsString(payload)).with(who)).andExpect(status().is2xxSuccessful()));
    }

    private record Person(String key, String email, String subject, String role, UUID id) {}

    @Test
    void VYB0959_AC1_anInventedTeamGoesFromAnEmptyPortfolioToApprovedRequirementsAndAReleaseThroughTheRunbooksSteps() throws Exception {
        String tag = unique("pilot").toLowerCase();
        UUID adminId = newAdministrator();
        String adminSub = jdbc.queryForObject("SELECT subject FROM app_user WHERE id = ?", String.class, adminId);
        JwtRequestPostProcessor admin = signedIn(adminSub, adminSub.substring(4) + "@it.test");

        // Step 1: the administrator makes the portfolio the team will work in.
        String productId = postJson("/api/v1/products", new java.util.LinkedHashMap<String, Object>() {{
            put("key", tag.toUpperCase().substring(0, Math.min(12, tag.length()))); put("name", tag + " product"); }}, admin).get("id").asText();
        String applicationId = postJson("/api/v1/products/" + productId + "/applications", java.util.Map.of("name", tag + " app"), admin).get("id").asText();
        String capabilityId = postJson("/api/v1/applications/" + applicationId + "/capabilities", java.util.Map.of("name", tag + " cap"), admin).get("id").asText();

        // Step 2: the administrator adds each person to the directory before they have ever signed in, with a role at the capability.
        // The people sign in later; the roles are waiting for them.
        record Row(String key, String role) {}
        List<Row> rows = List.of(new Row("analyst", "BUSINESS_ANALYST"), new Row("reviewer", "REVIEWER"), new Row("approver", "APPROVER"),
            new Row("developer", "DEVELOPER"), new Row("viewer", "VIEWER"));
        List<Person> people = new ArrayList<>();
        for (Row r : rows) {
            String email = r.key() + "." + tag + "@pilot.test";
            JsonNode created = postJson("/api/v1/users", java.util.Map.of("email", email, "displayName", "Pilot " + r.key(), "role", r.role(),
                "scopeType", "CAPABILITY", "scopeId", capabilityId), admin);
            people.add(new Person(r.key(), email, "kc-" + UUID.randomUUID(), r.role(), UUID.fromString(created.get("id").asText())));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_grant WHERE scope_id = ?::uuid AND revoked_at IS NULL", Integer.class, capabilityId))
            .as("a grant per person, at the capability, before anybody has signed in").isEqualTo(5);

        // Step 3: each person signs in for the first time. The subject Keycloak gives them is new to Vyoog; their email address finds the
        // row the administrator prepared, so the grants they were given apply to them and nobody ends up with two accounts.
        java.util.Map<String, JwtRequestPostProcessor> as = new java.util.HashMap<>();
        for (Person p : people) {
            JwtRequestPostProcessor token = signedIn(p.subject(), p.email());
            as.put(p.key(), token);
            mvc.perform(get("/api/v1/me").with(token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(p.id().toString()))
                .andExpect(jsonPath("$.platformAdministrator").value(false))
                .andExpect(jsonPath("$.activeGrants[0].role").value(p.role()))
                .andExpect(jsonPath("$.activeGrants[0].scopeId").value(capabilityId));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email LIKE ?", Integer.class, "%." + tag + "@pilot.test"))
            .as("first sign-in claimed the prepared rows, it did not add new ones").isEqualTo(5);

        // Step 4: the team. Creating one answers with it, id included (VYB-0961; it used to answer with nothing).
        String teamName = tag + " team";
        String teamId = postJson("/api/v1/teams", java.util.Map.of("name", teamName), admin).get("id").asText();
        assertThat(jdbc.queryForObject("SELECT name FROM team WHERE id = ?::uuid", String.class, teamId)).isEqualTo(teamName);
        for (Person p : people) {
            mvc.perform(put("/api/v1/teams/" + teamId + "/members/" + p.id()).with(admin)).andExpect(status().is2xxSuccessful());
        }
        UUID analystId = people.get(0).id();
        mvc.perform(put("/api/v1/teams/" + teamId + "/members/" + analystId + "/role").contentType(JSON)
            .content("{\"role\":\"LEAD\"}").with(admin)).andExpect(status().is2xxSuccessful());
        mvc.perform(get("/api/v1/teams/" + teamId + "/members").with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5));

        // Step 5: the lead gives the team its board (columns in the order the team works), in the team's own scope.
        postJson("/api/v1/boards/templates", java.util.Map.of("teamId", teamId, "name", tag + " board", "columns", List.of("DRAFT", "IN_REVIEW", "REVIEWED", "APPROVED"),
            "applicationId", applicationId), as.get("analyst"));
        mvc.perform(get("/api/v1/boards/templates").with(as.get("viewer"))).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.name == '" + tag + " board')].columns[0]").value("DRAFT"));

        // Step 6: the team's first requirements. The analyst writes three, each with acceptance criteria, and submits them. The viewer and
        // the developer cannot (a 403 is the product working, not a gap).
        mvc.perform(post("/api/v1/requirements").contentType(JSON).content(json.writeValueAsString(java.util.Map.of(
            "title", "Not allowed", "statement", "The system shall not be written by a viewer.", "capabilityId", capabilityId))).with(as.get("viewer")))
            .andExpect(status().isForbidden());
        List<String[]> reqs = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            JsonNode r = postJson("/api/v1/requirements", java.util.Map.of("title", tag + " requirement " + i, "statement",
                "The system shall let a pilot user complete step " + i + " within five seconds.", "type", "FUNCTIONAL", "priority", "MEDIUM",
                "capabilityId", capabilityId), as.get("analyst"));
            String id = r.get("id").asText();
            postJson("/api/v1/requirements/" + id + "/acceptance-criteria", java.util.Map.of("text", "Step " + i + " completes in under five seconds on the pilot data set."), as.get("analyst"));
            JsonNode submitted = body(mvc.perform(post("/api/v1/requirements/" + id + "/transition?revision=" + r.get("revision").asInt()).contentType(JSON)
                .content("{\"target\":\"IN_REVIEW\"}").with(as.get("analyst"))).andExpect(status().isOk()));
            reqs.add(new String[] {id, submitted.get("revision").asText()});
        }

        // Step 7: review and approval, by the people with those roles. The analyst cannot approve their own work (a 403), the reviewer
        // marks two reviewed, the approver approves them. The third is left in review.
        mvc.perform(post("/api/v1/requirements/" + reqs.get(0)[0] + "/transition?revision=" + reqs.get(0)[1]).contentType(JSON)
            .content("{\"target\":\"REVIEWED\"}").with(as.get("analyst"))).andExpect(status().isForbidden());
        for (int i = 0; i < 2; i++) {
            String[] r = reqs.get(i);
            JsonNode reviewed = body(mvc.perform(post("/api/v1/requirements/" + r[0] + "/transition?revision=" + r[1]).contentType(JSON)
                .content("{\"target\":\"REVIEWED\"}").with(as.get("reviewer"))).andExpect(status().isOk()));
            mvc.perform(post("/api/v1/requirements/" + r[0] + "/transition?revision=" + reviewed.get("revision").asInt()).contentType(JSON)
                .content("{\"target\":\"APPROVED\"}").with(as.get("approver"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        }
        mvc.perform(get("/api/v1/requirements?capabilityId=" + capabilityId + "&status=APPROVED").with(as.get("viewer"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/requirements?capabilityId=" + capabilityId + "&status=IN_REVIEW").with(as.get("viewer"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1));

        // Step 8: the first release: the approver names it and commits the approved requirements to it.
        String releaseId = postJson("/api/v1/releases", java.util.Map.of("name", tag + " release 1"), as.get("approver")).get("id").asText();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/releases/" + releaseId + "/scope").contentType(JSON).content(json.writeValueAsString(java.util.Map.of(
                "requirementId", reqs.get(i)[0], "reason", "Pilot scope"))).with(as.get("approver"))).andExpect(status().is2xxSuccessful());
        }
        mvc.perform(get("/api/v1/releases/" + releaseId + "/readiness").with(as.get("viewer"))).andExpect(status().isOk()).andExpect(jsonPath("$.committed").value(2));

        // Step 9: the team's own start screens answer for the pilot data, and the downloadable import template is there for the
        // existing spreadsheet the team will bring.
        mvc.perform(get("/api/v1/tasks/mine").with(as.get("reviewer"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/products/dashboard").with(as.get("viewer"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/import/templates/xlsx").with(as.get("analyst"))).andExpect(status().isOk());

        // Step 10: everything above is on the audit record, who did what.
        UUID approvedId = UUID.fromString(reqs.get(0)[0]);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE object_id = ?", Integer.class, approvedId))
            .as("create, submit, review and approve are each an audit event").isGreaterThanOrEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT actor_id) FROM audit_event WHERE object_id = ?", Integer.class, approvedId))
            .as("by three different people").isEqualTo(3);
    }
}
