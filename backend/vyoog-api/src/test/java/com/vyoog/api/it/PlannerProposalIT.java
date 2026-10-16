package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.KnownServiceScopes;
import com.vyoog.identity.ServiceAccountService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * VYB-0919 (D24, D51): a later change to a Function the Agile Planner already sent arrives as a proposal on its requirement, which a
 * person accepts or rejects. Over HTTP with real tokens: the Planner never changes a requirement, only a person does, under the normal
 * role and approval rules.
 */
@AutoConfigureMockMvc
class PlannerProposalIT extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ServiceAccountService serviceAccounts;

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private JwtRequestPostProcessor planner() {
        String clientId = unique("planner-client");
        serviceAccounts.create(unique("planner"), "Agile Planner (test)", clientId, List.of(KnownServiceScopes.PLANNER_REQUIREMENTS), null);
        return jwt().jwt(j -> j.subject("svc-" + clientId).claim("azp", clientId).issuedAt(java.time.Instant.now()));
    }

    private JwtRequestPostProcessor tokenOf(UUID user) {
        String sub = jdbc.queryForObject("SELECT subject FROM app_user WHERE id = ?", String.class, user);
        return jwt().jwt(j -> j.subject(sub).claim("email", sub.substring(4) + "@it.test").claim("preferred_username", sub.substring(4)).claim("azp", "vyoog-web"));
    }

    private static Map<String, Object> push(String fn, String title, List<String> criteria) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source_system", "agile_planner");
        m.put("source_function_id", fn);
        m.put("title", title);
        m.put("description", "The system shall " + title.toLowerCase() + ".");
        m.put("priority", "medium");
        m.put("acceptance_criteria", criteria);
        return m;
    }

    private ResultActions send(JwtRequestPostProcessor token, String key, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/planner/requirements").contentType(JSON).header("Idempotency-Key", key)
            .content(json.writeValueAsString(body)).with(token));
    }

    /** A Function the Planner has sent once, placed under a capability so the people there can act on it. */
    private record Seeded(JwtRequestPostProcessor token, String fn, UUID id, String key, Portfolio portfolio) {}

    private Seeded seed(List<String> criteria) throws Exception {
        var token = planner();
        String fn = unique("FN");
        Portfolio p = newPortfolio();
        String key = json.readTree(send(token, fn + "-1", push(fn, "Customers can pause a subscription", criteria))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("requirement_id").asText();
        UUID id = jdbc.queryForObject("SELECT id FROM requirement WHERE key = ?", UUID.class, key);
        jdbc.update("UPDATE requirement SET capability_id = ? WHERE id = ?", p.capabilityId(), id);
        return new Seeded(token, fn, id, key, p);
    }

    private JsonNode changed(Seeded s, String suffix, String title, List<String> criteria) throws Exception {
        return json.readTree(send(s.token(), s.fn() + "-" + suffix, push(s.fn(), title, criteria)).andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString());
    }

    private String stateOf(String proposalId) {
        return jdbc.queryForObject("SELECT state FROM planner_change_proposal WHERE id = ?::uuid", String.class, proposalId);
    }

    private int pending(UUID requirementId) {
        return jdbc.queryForObject("SELECT count(*) FROM planner_change_proposal WHERE requirement_id = ? AND state = 'PENDING'", Integer.class, requirementId);
    }

    private String url(Seeded s, String proposalId, String action) {
        return "/api/v1/requirements/" + s.id() + "/planner-proposals/" + proposalId + "/" + action;
    }

    @Test
    void VYB0919_AC1_aChangedFunctionWaitsAsAProposalAndTheRequirementIsUntouched() throws Exception {
        Seeded s = seed(List.of("A paused subscription is not billed"));
        UUID analyst = grantOnCapability(newUser("analyst"), AccessRole.BUSINESS_ANALYST, s.portfolio().capabilityId());
        UUID viewer = grantOnCapability(newUser("viewer"), AccessRole.VIEWER, s.portfolio().capabilityId());
        int revision = jdbc.queryForObject("SELECT revision FROM requirement WHERE id = ?", Integer.class, s.id());

        JsonNode out = changed(s, "2", "Customers can pause or cancel a subscription", List.of("Cancelling refunds the unused days"));
        assertThat(out.get("proposal").get("state").asText()).isEqualTo("pending");
        assertThat(out.get("requirement_id").asText()).isEqualTo(s.key());
        assertThat(out.get("status").asText()).as("the requirement's own status").isEqualTo("draft");

        Map<String, Object> r = jdbc.queryForMap("SELECT title, revision FROM requirement WHERE id = ?", s.id());
        assertThat(r.get("title")).isEqualTo("Customers can pause a subscription");
        assertThat(r.get("revision")).isEqualTo(revision);
        assertThat(pending(s.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor_type FROM audit_event WHERE object_id = ? AND action = 'planner.change_proposed'", String.class, s.id()))
            .isEqualTo("SERVICE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE user_id = ? AND kind = 'planner-change-proposed'", Integer.class, analyst))
            .as("the analyst who can edit it is told").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE user_id = ? AND kind = 'planner-change-proposed'", Integer.class, viewer))
            .as("a viewer cannot act on it, so is not told").isZero();
    }

    @Test
    void VYB0919_AC2_theSameChangeSentAgainIsTheSameProposal() throws Exception {
        Seeded s = seed(List.of("One"));
        JsonNode first = changed(s, "2", "A changed title", List.of("One", "Two"));
        String id = first.get("proposal").get("proposal_id").asText();
        // another key, same words
        assertThat(changed(s, "3", "A changed title", List.of("One", "Two")).get("proposal").get("proposal_id").asText()).isEqualTo(id);
        // the very same key again
        assertThat(changed(s, "2", "A changed title", List.of("One", "Two")).get("proposal").get("proposal_id").asText()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planner_change_proposal WHERE requirement_id = ?", Integer.class, s.id())).isEqualTo(1);
    }

    @Test
    void VYB0919_AC3_aNewerChangeReplacesTheOneNobodyHasDecidedAndSendingTheCurrentWordsClosesIt() throws Exception {
        Seeded s = seed(List.of("One"));
        UUID analyst = grantOnCapability(newUser("analyst"), AccessRole.BUSINESS_ANALYST, s.portfolio().capabilityId());
        String older = changed(s, "2", "First change", List.of("One")).get("proposal").get("proposal_id").asText();
        String newer = changed(s, "3", "Second change", List.of("One")).get("proposal").get("proposal_id").asText();
        assertThat(newer).isNotEqualTo(older);
        assertThat(stateOf(older)).isEqualTo("SUPERSEDED");
        assertThat(stateOf(newer)).isEqualTo("PENDING");
        mvc.perform(get("/api/v1/requirements/" + s.id() + "/planner-proposals").with(tokenOf(analyst))).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(newer));

        // the Planner goes back to the words the requirement already has: nothing is left to decide
        send(s.token(), s.fn() + "-4", push(s.fn(), "Customers can pause a subscription", List.of("One"))).andExpect(status().isOk());
        assertThat(stateOf(newer)).isEqualTo("SUPERSEDED");
        assertThat(pending(s.id())).isZero();
    }

    @Test
    void VYB0919_AC4_acceptingMakesItARevisionByThePersonAddsCriteriaAndRemovesNone() throws Exception {
        Seeded s = seed(List.of("Keep me", "Shared"));
        UUID analyst = grantOnCapability(newUser("analyst"), AccessRole.BUSINESS_ANALYST, s.portfolio().capabilityId());
        Map<String, Object> body = push(s.fn(), "Customers can pause or cancel", List.of("Shared", "A new one"));
        body.put("priority", "critical");
        String proposal = json.readTree(send(s.token(), s.fn() + "-2", body).andExpect(status().isAccepted()).andReturn().getResponse()
            .getContentAsString()).get("proposal").get("proposal_id").asText();
        Map<String, Object> before = jdbc.queryForMap("SELECT revision, type, capability_id FROM requirement WHERE id = ?", s.id());

        mvc.perform(post(url(s, proposal, "accept")).with(tokenOf(analyst))).andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("ACCEPTED")).andExpect(jsonPath("$.appliedRevision").value((int) before.get("revision") + 1));

        Map<String, Object> r = jdbc.queryForMap("SELECT title, statement, priority, revision, type, capability_id, updated_by FROM requirement WHERE id = ?", s.id());
        assertThat(r.get("title")).isEqualTo("Customers can pause or cancel");
        assertThat(r.get("statement")).isEqualTo("The system shall customers can pause or cancel.");
        assertThat(r.get("priority")).isEqualTo("CRITICAL");
        assertThat(r.get("revision")).isEqualTo((int) before.get("revision") + 1);
        assertThat(r.get("type")).isEqualTo(before.get("type"));
        assertThat(r.get("capability_id")).isEqualTo(before.get("capability_id"));
        assertThat(r.get("updated_by")).as("made by the person who accepted").isEqualTo(analyst);
        assertThat(jdbc.queryForList("SELECT text FROM acceptance_criterion WHERE requirement_id = ? ORDER BY ordinal", String.class, s.id()))
            .as("one criterion added, none removed").containsExactly("Keep me", "Shared", "A new one");
        assertThat(jdbc.queryForObject("SELECT decided_by FROM planner_change_proposal WHERE id = ?::uuid", UUID.class, proposal)).isEqualTo(analyst);
        assertThat(auditCount(s.id(), "planner.change_accepted")).isEqualTo(1);

        // it is decided: a second accept is refused, and the Planner repeating the same words changes nothing
        mvc.perform(post(url(s, proposal, "accept")).with(tokenOf(analyst))).andExpect(status().isConflict());
        send(s.token(), s.fn() + "-3", body).andExpect(status().isOk());
        assertThat(pending(s.id())).isZero();
    }

    @Test
    void VYB0919_AC5_rejectingChangesNothingAndTheSameWordsDoNotComeBack() throws Exception {
        Seeded s = seed(List.of("One"));
        UUID analyst = grantOnCapability(newUser("analyst"), AccessRole.BUSINESS_ANALYST, s.portfolio().capabilityId());
        String proposal = changed(s, "2", "Rejected wording", List.of("One")).get("proposal").get("proposal_id").asText();
        int revision = jdbc.queryForObject("SELECT revision FROM requirement WHERE id = ?", Integer.class, s.id());

        mvc.perform(post(url(s, proposal, "reject")).with(tokenOf(analyst)).contentType(JSON).content("{\"note\":\"Not what we agreed\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("REJECTED")).andExpect(jsonPath("$.decisionNote").value("Not what we agreed"));
        assertThat(jdbc.queryForObject("SELECT revision FROM requirement WHERE id = ?", Integer.class, s.id())).isEqualTo(revision);
        assertThat(jdbc.queryForObject("SELECT title FROM requirement WHERE id = ?", String.class, s.id())).isEqualTo("Customers can pause a subscription");
        assertThat(auditCount(s.id(), "planner.change_rejected")).isEqualTo(1);

        send(s.token(), s.fn() + "-3", push(s.fn(), "Rejected wording", List.of("One"))).andExpect(status().isOk()); // the same words: not asked again
        assertThat(pending(s.id())).isZero();
        changed(s, "4", "Different wording", List.of("One")); // a different change is a new proposal
        assertThat(pending(s.id())).isEqualTo(1);
    }

    @Test
    void VYB0919_AC6_anApprovedRequirementCannotAcceptItAndTheProposalKeepsWaiting() throws Exception {
        Seeded s = seed(List.of("One"));
        UUID analyst = grantOnCapability(newUser("analyst"), AccessRole.BUSINESS_ANALYST, s.portfolio().capabilityId());
        String proposal = changed(s, "2", "Wording after approval", List.of("One")).get("proposal").get("proposal_id").asText();
        jdbc.update("UPDATE requirement SET status = 'APPROVED' WHERE id = ?", s.id());

        mvc.perform(post(url(s, proposal, "accept")).with(tokenOf(analyst))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("reject the proposal"))); // our own message, not the edit path's
        assertThat(stateOf(proposal)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT title FROM requirement WHERE id = ?", String.class, s.id())).isEqualTo("Customers can pause a subscription");
        mvc.perform(post(url(s, proposal, "reject")).with(tokenOf(analyst))).andExpect(status().isOk()); // rejecting is still open to them
    }

    @Test
    void VYB0919_AC7_onlyAPersonWhoCanEditItDecidesAndAnyoneSignedInMayRead() throws Exception {
        Seeded s = seed(List.of("One"));
        String proposal = changed(s, "2", "Waiting wording", List.of("One")).get("proposal").get("proposal_id").asText();
        UUID viewer = grantOnCapability(newUser("viewer"), AccessRole.VIEWER, s.portfolio().capabilityId());
        UUID elsewhere = grantOnCapability(newUser("analyst-elsewhere"), AccessRole.BUSINESS_ANALYST, newPortfolio().capabilityId());

        for (UUID nope : List.of(viewer, elsewhere)) {
            mvc.perform(post(url(s, proposal, "accept")).with(tokenOf(nope))).andExpect(status().isForbidden());
            mvc.perform(post(url(s, proposal, "reject")).with(tokenOf(nope))).andExpect(status().isForbidden());
        }
        mvc.perform(post(url(s, proposal, "accept")).with(s.token())).andExpect(status().isForbidden()); // the Planner itself
        mvc.perform(post(url(s, proposal, "accept"))).andExpect(status().isUnauthorized());
        assertThat(stateOf(proposal)).isEqualTo("PENDING");
        mvc.perform(get("/api/v1/requirements/" + s.id() + "/planner-proposals").with(tokenOf(viewer))).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(proposal));

        // another requirement's proposal id is not found under this one
        Seeded other = seed(List.of("One"));
        UUID analystOther = grantOnCapability(newUser("analyst"), AccessRole.BUSINESS_ANALYST, other.portfolio().capabilityId());
        mvc.perform(post(url(other, proposal, "accept")).with(tokenOf(analystOther))).andExpect(status().isNotFound());
    }

    @Test
    void VYB0919_AC8_changesSentAtTheSameMomentLeaveOnePendingAndNoFailure() throws Exception {
        Seeded s = seed(List.of("One"));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                final int n = i;
                results.add(pool.submit(() -> send(s.token(), s.fn() + "-c" + n, push(s.fn(), "Concurrent change " + n, List.of("One")))
                    .andReturn().getResponse().getStatus()));
            }
            for (Future<Integer> f : results) assertThat(f.get()).isEqualTo(202);
        } finally {
            pool.shutdownNow();
        }
        assertThat(pending(s.id())).as("one change is left waiting").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planner_change_proposal WHERE requirement_id = ?", Integer.class, s.id())).isEqualTo(6);
    }

    @Test
    void VYB0919_AC9_twoPeopleDecidingTheSameProposalAtTheSameMomentOneWins() throws Exception {
        Seeded s = seed(List.of("One"));
        UUID a = grantOnCapability(newUser("analyst"), AccessRole.BUSINESS_ANALYST, s.portfolio().capabilityId());
        UUID b = grantOnCapability(newUser("analyst"), AccessRole.BUSINESS_ANALYST, s.portfolio().capabilityId());
        String proposal = changed(s, "2", "Contested wording", List.of("One", "Added")).get("proposal").get("proposal_id").asText();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> fa = pool.submit(() -> mvc.perform(post(url(s, proposal, "accept")).with(tokenOf(a))).andReturn().getResponse().getStatus());
            Future<Integer> fb = pool.submit(() -> mvc.perform(post(url(s, proposal, "accept")).with(tokenOf(b))).andReturn().getResponse().getStatus());
            assertThat(List.of(fa.get(), fb.get())).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM acceptance_criterion WHERE requirement_id = ? AND text = 'Added'", Integer.class, s.id())).isEqualTo(1);
        assertThat(auditCount(s.id(), "planner.change_accepted")).isEqualTo(1);
    }
}
