package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.identity.KnownServiceScopes;
import com.vyoog.identity.ServiceAccountService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
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
 * VYB-0915 (D24, D50): the Agile Planner sends a Function to be created here as a draft requirement. Over HTTP with real tokens: a
 * registered service account holding {@code planner:requirements} is the Planner, anything else is refused, and the same Function
 * never becomes two requirements however it is sent.
 */
@AutoConfigureMockMvc
class PlannerIntakeIT extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ServiceAccountService serviceAccounts;

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String URL = "/api/v1/planner/requirements";

    private JwtRequestPostProcessor planner() {
        String clientId = unique("planner-client");
        serviceAccounts.create(unique("planner"), "Agile Planner (test)", clientId, List.of(KnownServiceScopes.PLANNER_REQUIREMENTS), null);
        return jwt().jwt(j -> j.subject("svc-" + clientId).claim("azp", clientId));
    }

    private static Map<String, Object> push(String functionId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source_system", "agile_planner");
        m.put("source_function_id", functionId);
        m.put("source_feature_id", "FEAT-7");
        m.put("source_capability_id", "CAP-3");
        m.put("source_goal_id", "GOAL-1");
        m.put("title", "Customers can pause a subscription");
        m.put("description", "The system shall let a customer pause a subscription for up to 90 days.");
        m.put("priority", "high");
        m.put("acceptance_criteria", List.of("A paused subscription is not billed", "Pausing is refused after 90 days"));
        return m;
    }

    private ResultActions send(JwtRequestPostProcessor token, String key, Map<String, Object> body) throws Exception {
        var req = post(URL).contentType(JSON).content(json.writeValueAsString(body));
        if (key != null) req = req.header("Idempotency-Key", key);
        return mvc.perform(req.with(token));
    }

    private int requirementsFor(String functionId) {
        return jdbc.queryForObject("SELECT count(*) FROM planner_function_link WHERE source_function_id = ?", Integer.class, functionId);
    }

    private UUID requirementOf(String key) {
        return jdbc.queryForObject("SELECT id FROM requirement WHERE key = ?", UUID.class, key);
    }

    @Test
    void VYB0915_AC1_aPushCreatesADraftRequirementWithItsCriteriaAndABackReference() throws Exception {
        var token = planner();
        String fn = unique("FN");
        JsonNode out = json.readTree(send(token, fn + "-t1", push(fn)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("draft"))
            .andExpect(jsonPath("$.traceability.source_function_id").value(fn))
            .andExpect(jsonPath("$.created_at").exists())
            .andReturn().getResponse().getContentAsString());

        UUID id = requirementOf(out.get("requirement_id").asText());
        Map<String, Object> r = jdbc.queryForMap("SELECT status, type, priority, title, statement, capability_id, created_by FROM requirement WHERE id = ?", id);
        assertThat(r.get("status")).isEqualTo("DRAFT");
        assertThat(r.get("type")).isEqualTo("FUNCTIONAL");
        assertThat(r.get("priority")).isEqualTo("HIGH");
        assertThat(r.get("title")).isEqualTo("Customers can pause a subscription");
        assertThat(r.get("statement")).isEqualTo("The system shall let a customer pause a subscription for up to 90 days.");
        assertThat(r.get("capability_id")).as("placed nowhere: the Planner's capability belongs to another system").isNull();
        assertThat(r.get("created_by")).as("made by a service, not a person").isNull();
        assertThat(jdbc.queryForList("SELECT text FROM acceptance_criterion WHERE requirement_id = ? ORDER BY ordinal", String.class, id))
            .containsExactly("A paused subscription is not billed", "Pausing is refused after 90 days");
        Map<String, Object> link = jdbc.queryForMap("SELECT * FROM planner_function_link WHERE requirement_id = ?", id);
        assertThat(link.get("source_function_id")).isEqualTo(fn);
        assertThat(link.get("source_feature_id")).isEqualTo("FEAT-7");
        assertThat(link.get("source_capability_id")).isEqualTo("CAP-3");
        assertThat(link.get("source_goal_id")).isEqualTo("GOAL-1");
        assertThat(jdbc.queryForObject("SELECT actor_type FROM audit_event WHERE object_id = ? AND action = 'requirement.created'", String.class, id))
            .as("audited as a service").isEqualTo("SERVICE");
    }

    @Test
    void VYB0915_AC2_theSameIdempotencyKeyAnswersWithTheRequirementItMade() throws Exception {
        var token = planner();
        String fn = unique("FN");
        String key = fn + "-t1";
        String first = send(token, key, push(fn)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String again = send(token, key, push(fn)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(again).get("requirement_id").asText()).isEqualTo(json.readTree(first).get("requirement_id").asText());
        assertThat(requirementsFor(fn)).isEqualTo(1);
    }

    @Test
    void VYB0915_AC3_theSameFunctionAgainIsARepeatWhenUnchangedAndAProposalWhenChanged() throws Exception {
        var token = planner();
        String fn = unique("FN");
        String key = json.readTree(send(token, fn + "-2026-10-01", push(fn)).andExpect(status().isCreated()).andReturn()
            .getResponse().getContentAsString()).get("requirement_id").asText();
        UUID id = requirementOf(key);

        // a person here edits it meanwhile; the Planner sending the same words again must not read as a change, and must not undo the edit
        jdbc.update("UPDATE requirement SET title = 'Edited here by a person' WHERE id = ?", id);
        send(token, fn + "-2026-10-02", push(fn)).andExpect(status().isOk()).andExpect(jsonPath("$.requirement_id").value(key));
        assertThat(jdbc.queryForObject("SELECT title FROM requirement WHERE id = ?", String.class, id)).isEqualTo("Edited here by a person");

        // a changed Function is not applied behind anyone's back: it waits as a proposal (VYB-0919)
        Map<String, Object> changed = push(fn);
        changed.put("title", "Customers can pause or cancel a subscription");
        changed.put("acceptance_criteria", List.of("Something else entirely"));
        send(token, fn + "-2026-10-03", changed).andExpect(status().isAccepted())
            .andExpect(jsonPath("$.requirement_id").value(key)).andExpect(jsonPath("$.proposal.state").value("pending"));
        assertThat(jdbc.queryForObject("SELECT title FROM requirement WHERE id = ?", String.class, id)).isEqualTo("Edited here by a person");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM acceptance_criterion WHERE requirement_id = ?", Integer.class, id)).isEqualTo(2);
        assertThat(requirementsFor(fn)).isEqualTo(1);
    }

    @Test
    void VYB0915_AC4_priorityIsMappedAndADescriptionlessFunctionIsStatedByItsTitle() throws Exception {
        var token = planner();
        record Case(String sent, String expected) {}
        for (Case c : List.of(new Case("CRITICAL", "CRITICAL"), new Case("Low", "LOW"), new Case("urgent", "MEDIUM"), new Case(null, "MEDIUM"))) {
            String fn = unique("FN");
            Map<String, Object> body = push(fn);
            body.put("priority", c.sent());
            body.put("description", "   ");
            body.put("acceptance_criteria", new ArrayList<>(java.util.Arrays.asList("  ", null, "Only real criterion")));
            String key = json.readTree(send(token, fn + "-1", body).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString()).get("requirement_id").asText();
            Map<String, Object> r = jdbc.queryForMap("SELECT priority, statement FROM requirement WHERE key = ?", key);
            assertThat(r.get("priority")).as("sent " + c.sent()).isEqualTo(c.expected());
            assertThat(r.get("statement")).isEqualTo("Customers can pause a subscription");
            assertThat(jdbc.queryForList("SELECT text FROM acceptance_criterion WHERE requirement_id = ?", String.class, requirementOf(key)))
                .containsExactly("Only real criterion");
        }
    }

    @Test
    void VYB0915_AC5_onlyARegisteredServiceAccountWithTheScopeMayPush() throws Exception {
        String fn = unique("FN");
        // a person
        UUID person = newUser("someone");
        String sub = jdbc.queryForObject("SELECT subject FROM app_user WHERE id = ?", String.class, person);
        send(jwt().jwt(j -> j.subject(sub).claim("email", "p@it.test").claim("azp", "vyoog-web")), fn + "-1", push(fn)).andExpect(status().isForbidden());
        // a registered service account without the scope
        String other = unique("ci-client");
        serviceAccounts.create(unique("ci"), "CI (test)", other, List.of(KnownServiceScopes.CI_INGEST), null);
        send(jwt().jwt(j -> j.subject("svc").claim("azp", other)), fn + "-1", push(fn)).andExpect(status().isForbidden());
        // a client nobody registered
        send(jwt().jwt(j -> j.subject("svc").claim("azp", unique("stranger"))), fn + "-1", push(fn)).andExpect(status().isForbidden());
        // no token at all
        mvc.perform(post(URL).contentType(JSON).header("Idempotency-Key", fn + "-1").content(json.writeValueAsString(push(fn))))
            .andExpect(status().isUnauthorized());
        assertThat(requirementsFor(fn)).as("nothing was created by any of them").isZero();
    }

    @Test
    void VYB0915_AC6_aBadPushIsRefusedWithAReasonAndCreatesNothing() throws Exception {
        var token = planner();
        String fn = unique("FN");

        send(token, null, push(fn)).andExpect(status().isBadRequest()); // no Idempotency-Key
        Map<String, Object> wrongSystem = push(fn);
        wrongSystem.put("source_system", "jira");
        send(token, fn + "-1", wrongSystem).andExpect(status().isBadRequest());
        Map<String, Object> noFunction = push(fn);
        noFunction.put("source_function_id", " ");
        send(token, fn + "-1", noFunction).andExpect(status().isBadRequest());
        Map<String, Object> noTitle = push(fn);
        noTitle.put("title", "");
        send(token, fn + "-1", noTitle).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("title is required"));
        Map<String, Object> tooMany = push(fn);
        tooMany.put("acceptance_criteria", java.util.Collections.nCopies(51, "x"));
        send(token, fn + "-1", tooMany).andExpect(status().isBadRequest());
        assertThat(requirementsFor(fn)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_key WHERE key LIKE ?", Integer.class, fn + "%")).isZero();
    }

    @Test
    void VYB0915_AC7_theSameFunctionSentAtTheSameMomentBecomesOneRequirement() throws Exception {
        var token = planner();
        String fn = unique("FN");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                final int n = i;
                // half share an Idempotency-Key and half do not: both must settle on one requirement
                Callable<String> call = () -> {
                    var res = send(token, fn + "-" + (n % 4), push(fn)).andReturn().getResponse();
                    return res.getStatus() + ":" + new ObjectMapper().readTree(res.getContentAsString()).get("requirement_id").asText();
                };
                results.add(pool.submit(call));
            }
            List<String> keys = new ArrayList<>();
            for (Future<String> f : results) {
                String[] parts = f.get().split(":");
                assertThat(parts[0]).isIn("200", "201");
                keys.add(parts[1]);
            }
            assertThat(keys.stream().distinct().toList()).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(requirementsFor(fn)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM requirement r JOIN planner_function_link l ON l.requirement_id = r.id WHERE l.source_function_id = ?",
            Integer.class, fn)).isEqualTo(1);
    }
}
