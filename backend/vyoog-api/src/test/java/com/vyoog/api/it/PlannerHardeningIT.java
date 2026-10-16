package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.api.config.PlannerLimits;
import com.vyoog.identity.KnownServiceScopes;
import com.vyoog.identity.ServiceAccountService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * VYB-0921 (D54): the limits on the Agile Planner's push endpoint, over HTTP: a body that is too large (413), too many pushes from one
 * service account in a minute (429 with Retry-After), a token that is too old or has no issue time (401), and an Idempotency-Key that is not
 * printable ASCII (400). Each limit is lowered for its test through the one bean that holds it, and put back.
 */
@AutoConfigureMockMvc
class PlannerHardeningIT extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ServiceAccountService serviceAccounts;
    @Autowired PlannerLimits limits;

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String URL = "/api/v1/planner/requirements";

    @AfterEach
    void putTheLimitsBack() {
        ReflectionTestUtils.setField(limits, "maxBodyBytes", 262144);
        ReflectionTestUtils.setField(limits, "pushesPerMinute", 600);
        ReflectionTestUtils.setField(limits, "maxTokenAgeMinutes", 15);
    }

    private String registerClient() {
        String clientId = unique("planner-client");
        serviceAccounts.create(unique("planner"), "Agile Planner (test)", clientId, List.of(KnownServiceScopes.PLANNER_REQUIREMENTS), null);
        return clientId;
    }

    private static JwtRequestPostProcessor tokenOf(String clientId, Instant issuedAt) {
        return jwt().jwt(j -> {
            j.subject("svc-" + clientId).claim("azp", clientId);
            if (issuedAt != null) j.issuedAt(issuedAt);
        });
    }

    private static Map<String, Object> push(String fn) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source_system", "agile_planner");
        m.put("source_function_id", fn);
        m.put("title", "Customers can pause a subscription");
        m.put("description", "The system shall let a customer pause a subscription.");
        m.put("priority", "high");
        m.put("acceptance_criteria", List.of("A paused subscription is not billed"));
        return m;
    }

    private ResultActions send(JwtRequestPostProcessor token, String key, String body) throws Exception {
        return mvc.perform(post(URL).contentType(JSON).header("Idempotency-Key", key).content(body).with(token));
    }

    private int created(String fn) {
        return jdbc.queryForObject("SELECT count(*) FROM planner_function_link WHERE source_function_id = ?", Integer.class, fn);
    }

    /** The one-minute window is fixed: start early in a minute so a test's few calls cannot straddle two windows. */
    private void startEarlyInAMinute() throws InterruptedException {
        double second = jdbc.queryForObject("SELECT extract(second FROM clock_timestamp())::float8", Double.class);
        if (second > 45) Thread.sleep((long) ((60 - second) * 1000) + 300);
    }

    @Test
    void VYB0921_AC6_aBodyOverTheLimitIsRefusedWithoutBeingReadAndOneWithinItIsAccepted() throws Exception {
        String client = registerClient();
        String fn = unique("FN");
        // the default limit, 256 KiB, with a large but valid-looking body
        Map<String, Object> big = push(fn);
        big.put("description", "x".repeat(300_000));
        send(tokenOf(client, Instant.now()), fn + "-1", json.writeValueAsString(big)).andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.type").value("https://vyoog.dev/problems/payload-too-large")).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("262144")));
        assertThat(created(fn)).isZero();

        ReflectionTestUtils.setField(limits, "maxBodyBytes", 2048);
        send(tokenOf(client, Instant.now()), fn + "-2", json.writeValueAsString(push(fn))).andExpect(status().isCreated());
        Map<String, Object> over = push(unique("FN"));
        over.put("description", "y".repeat(2100));
        send(tokenOf(client, Instant.now()), unique("k"), json.writeValueAsString(over)).andExpect(status().isPayloadTooLarge());
    }

    @Test
    void VYB0921_AC7_aServiceAccountOverItsPushesPerMinuteIsToldToWaitAndAnotherIsNotAffected() throws Exception {
        startEarlyInAMinute();
        ReflectionTestUtils.setField(limits, "pushesPerMinute", 3);
        String busy = registerClient(), quiet = registerClient();
        List<String> functions = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String fn = unique("FN");
            functions.add(fn);
            send(tokenOf(busy, Instant.now()), fn + "-1", json.writeValueAsString(push(fn))).andExpect(status().isCreated());
        }
        String refused = unique("FN");
        send(tokenOf(busy, Instant.now()), refused + "-1", json.writeValueAsString(push(refused))).andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After")).andExpect(jsonPath("$.type").value("https://vyoog.dev/problems/rate-limit-exceeded"));
        assertThat(created(refused)).as("the refused push did nothing").isZero();

        String other = unique("FN");
        send(tokenOf(quiet, Instant.now()), other + "-1", json.writeValueAsString(push(other))).andExpect(status().isCreated());
    }

    @Test
    void VYB0921_AC8_aRefusedCallerDoesNotUseARegisteredAccountsAllowance() throws Exception {
        startEarlyInAMinute();
        ReflectionTestUtils.setField(limits, "pushesPerMinute", 2);
        String stranger = unique("stranger-client"); // never registered
        for (int i = 0; i < 5; i++) {
            send(tokenOf(stranger, Instant.now()), unique("k"), json.writeValueAsString(push(unique("FN")))).andExpect(status().isForbidden());
        }
        String client = registerClient();
        String fn = unique("FN");
        send(tokenOf(client, Instant.now()), fn + "-1", json.writeValueAsString(push(fn))).andExpect(status().isCreated());
    }

    @Test
    void VYB0921_AC9_aTokenTooOldWithNoIssueTimeOrIssuedInTheFutureIsRefusedAndAFreshOneIsNot() throws Exception {
        String client = registerClient();
        String fn = unique("FN");

        for (Instant bad : new Instant[] {Instant.now().minusSeconds(30 * 60), null, Instant.now().plusSeconds(3600)}) {
            send(tokenOf(client, bad), unique("k"), json.writeValueAsString(push(fn))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://vyoog.dev/problems/token-too-old")).andExpect(header().exists("WWW-Authenticate"));
        }
        assertThat(created(fn)).isZero();
        send(tokenOf(client, Instant.now().minusSeconds(10 * 60)), fn + "-1", json.writeValueAsString(push(fn))).andExpect(status().isCreated());

        ReflectionTestUtils.setField(limits, "maxTokenAgeMinutes", 0);
        String fn2 = unique("FN");
        send(tokenOf(client, Instant.now().minusSeconds(3 * 3600)), fn2 + "-1", json.writeValueAsString(push(fn2))).andExpect(status().isCreated());
    }

    @Test
    void VYB0921_AC10_anIdempotencyKeyThatIsNotPrintableAsciiIsRefused() throws Exception {
        String client = registerClient();
        String fn = unique("FN");
        for (String bad : new String[] {"has a space", "tab\there", "café-1"}) {
            send(tokenOf(client, Instant.now()), bad, json.writeValueAsString(push(fn))).andExpect(status().isBadRequest());
        }
        assertThat(created(fn)).isZero();
        send(tokenOf(client, Instant.now()), fn + "-2026-10-16T09:00:00Z", json.writeValueAsString(push(fn))).andExpect(status().isCreated());
    }
}
