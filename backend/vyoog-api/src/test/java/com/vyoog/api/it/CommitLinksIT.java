package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.evidence.CommitIngestService;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.KnownServiceScopes;
import com.vyoog.identity.ScopeType;
import com.vyoog.identity.ServiceAccountService;
import com.vyoog.integration.IntegrationService;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementLinkedWorkService;
import com.vyoog.trace.TraceGraphAssemblyService;
import com.vyoog.trace.TraceObjectType;
import java.util.List;
import java.util.Map;
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
 * VYB-0922 (D53): the git connection's repository address is used for commit links wherever a commit is shown (the trace graph, and the
 * requirement's linked work), and the git and CI connections show that they are in use when data arrives through the service-account
 * endpoints. Real services and HTTP against a real PostgreSQL.
 */
@AutoConfigureMockMvc
class CommitLinksIT extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired IntegrationService integrations;
    @Autowired CommitIngestService commitIngest;
    @Autowired TraceGraphAssemblyService graph;
    @Autowired RequirementLinkedWorkService linkedWork;
    @Autowired ServiceAccountService serviceAccounts;

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String REPO = "https://github.com/evyoog/demo";

    @BeforeEach
    @AfterEach
    void resetGitAndCi() {
        // these two registry rows are shared by every test in this database
        jdbc.update("UPDATE integration_connection SET config = NULL, connected = false, degraded = false, failure_count = 0, last_inbound_at = NULL WHERE key IN ('git', 'ci')");
    }

    private JwtRequestPostProcessor admin() {
        String id = unique("admin");
        UUID user = users.upsert("sub-" + id, id + "@it.test", id).getId();
        grants.grant(user, AccessRole.ADMINISTRATOR, ScopeType.PLATFORM, null, null, user);
        return jwt().jwt(j -> j.subject("sub-" + id).claim("email", id + "@it.test").claim("preferred_username", id).claim("azp", "vyoog-web"));
    }

    private JwtRequestPostProcessor ciBot() {
        String clientId = unique("ci-client");
        serviceAccounts.create(unique("ci"), "CI (test)", clientId, List.of(KnownServiceScopes.CI_INGEST), null);
        return jwt().jwt(j -> j.subject("svc-" + clientId).claim("azp", clientId));
    }

    private String sha() {
        return UUID.randomUUID().toString().replace("-", "") + "00000000";
    }

    private Requirement committedTo(String sha, String subject) {
        Requirement r = newRequirement(newPortfolio(), newUser("author"));
        commitIngest.ingest(sha, subject + "\n\nRequirement: " + r.getKey(), "dev@it.test");
        return r;
    }

    private JsonNode healthOf(String key) throws Exception {
        JsonNode all = json.readTree(mvc.perform(get("/api/v1/integrations/health").with(admin())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (JsonNode n : all) if (n.get("key").asText().equals(key)) return n;
        throw new AssertionError("no connection " + key);
    }

    @Test
    void VYB0922_AC5_aCommitInTheTraceGraphIsNamedByItsShaAndSubjectAndLinksToTheConfiguredRepository() {
        integrations.setConfig("git", "{\"repoUrl\":\"" + REPO + ".git/\"}");
        String sha = sha();
        Requirement r = committedTo(sha, "Pause stops billing");

        var node = graph.graphFor(TraceObjectType.REQUIREMENT, r.getId(), 2).nodes().stream().filter(n -> n.type().equals("CODE")).findFirst().orElseThrow();
        assertThat(node.label()).isEqualTo("Commit " + sha.substring(0, 7) + " — Pause stops billing");
        assertThat(node.href()).isEqualTo(REPO + "/commit/" + sha);
    }

    @Test
    void VYB0922_AC5_theTraceGraphEndpointCarriesTheLinkToTheScreenAndLeavesItOutWhenThereIsNone() throws Exception {
        integrations.setConfig("git", "{\"repoUrl\":\"" + REPO + "\"}");
        String sha = sha();
        Requirement r = committedTo(sha, "Seen over HTTP");

        JsonNode withRepo = json.readTree(mvc.perform(get("/api/v1/trace/REQUIREMENT/" + r.getId() + "/graph").with(admin()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode code = null;
        for (JsonNode n : withRepo.get("nodes")) if (n.get("type").asText().equals("CODE")) code = n;
        assertThat(code).isNotNull();
        assertThat(code.get("href").asText()).isEqualTo(REPO + "/commit/" + sha);
        assertThat(code.get("label").asText()).startsWith("Commit " + sha.substring(0, 7));

        integrations.setConfig("git", "{}");
        JsonNode without = json.readTree(mvc.perform(get("/api/v1/trace/REQUIREMENT/" + r.getId() + "/graph").with(admin()))
            .andReturn().getResponse().getContentAsString());
        for (JsonNode n : without.get("nodes")) if (n.get("type").asText().equals("CODE")) assertThat(n.get("href") == null || n.get("href").isNull()).isTrue();
    }

    @Test
    void VYB0922_AC6_withNoRepositoryAddressTheCommitIsStillNamedAndHasNoLinkNeverABrokenOne() {
        String sha = sha();
        Requirement r = committedTo(sha, "No repository set");

        var node = graph.graphFor(TraceObjectType.REQUIREMENT, r.getId(), 2).nodes().stream().filter(n -> n.type().equals("CODE")).findFirst().orElseThrow();
        assertThat(node.label()).startsWith("Commit " + sha.substring(0, 7));
        assertThat(node.href()).isNull();
        var commit = linkedWork.of(r.getId()).commits().get(0);
        assertThat(commit.href()).isNull();
        assertThat(commit.shortSha()).isEqualTo(sha.substring(0, 7));
    }

    @Test
    void VYB0922_AC7_aRequirementsLinkedWorkListsItsCommitsNewestFirstWithTheirLinks() throws Exception {
        integrations.setConfig("git", "{\"repoUrl\":\"" + REPO + "\"}");
        Requirement r = newRequirement(newPortfolio(), newUser("author"));
        String first = sha(), second = sha();
        commitIngest.ingest(first, "First change\n\nRequirement: " + r.getKey(), null);
        Thread.sleep(20);
        commitIngest.ingest(second, "Second change\n\nRequirement: " + r.getKey(), null);

        var commits = linkedWork.of(r.getId()).commits();
        assertThat(commits).hasSize(2);
        assertThat(commits.get(0).subject()).isEqualTo("Second change");
        assertThat(commits.get(0).href()).isEqualTo(REPO + "/commit/" + second);
        assertThat(commits.get(1).href()).isEqualTo(REPO + "/commit/" + first);
        assertThat(linkedWork.of(newRequirement(newPortfolio(), newUser("other")).getId()).commits()).isEmpty();
    }

    @Test
    void VYB0922_AC8_anAddressThatIsNotSafeToLinkToIsRefusedWhenItIsSavedAndTheStoredOneStays() throws Exception {
        integrations.setConfig("git", "{\"repoUrl\":\"" + REPO + "\"}");
        var admin = admin();
        for (String bad : new String[] {"http://github.com/evyoog/demo", "javascript:alert(1)", "https://u:p@github.com/x", "https://github.com/x?y=1"}) {
            mvc.perform(put("/api/v1/integrations/git/config").with(admin).contentType(JSON)
                    .content(json.writeValueAsString(Map.of("configJson", "{\"repoUrl\":\"" + bad + "\"}"))))
                .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT config FROM integration_connection WHERE key = 'git'", String.class)).contains(REPO);
        mvc.perform(put("/api/v1/integrations/git/config").with(admin).contentType(JSON)
                .content(json.writeValueAsString(Map.of("configJson", "{\"repoUrl\":\"https://github.com/evyoog/other\"}"))))
            .andExpect(status().isOk());
    }

    @Test
    void VYB0922_AC9_aCommitArrivingThroughTheCiEndpointShowsTheGitConnectionInUseAndATestRunShowsCi() throws Exception {
        JsonNode before = healthOf("git");
        assertThat(before.get("state").asText()).isEqualTo("NOT_CONNECTED");
        assertThat(before.get("lastSuccessAt").isNull()).isTrue();

        var bot = ciBot();
        mvc.perform(post("/api/v1/ci/commits").with(bot).contentType(JSON)
                .content(json.writeValueAsString(Map.of("sha", sha(), "message", "A commit with no requirement"))))
            .andExpect(status().isCreated());

        JsonNode git = healthOf("git");
        assertThat(git.get("state").asText()).isEqualTo("HEALTHY");
        assertThat(git.get("lastSuccessAt").isNull()).as("it says when").isFalse();
        assertThat(healthOf("ci").get("state").asText()).as("ci is unaffected by a commit").isEqualTo("NOT_CONNECTED");

        mvc.perform(post("/api/v1/ci/test-runs").with(bot).contentType(JSON)
                .content(json.writeValueAsString(Map.of("buildLabel", unique("build"), "source", "ci",
                    "results", List.of(Map.of("testKey", unique("T"), "result", "PASS"))))))
            .andExpect(status().isCreated());
        JsonNode ci = healthOf("ci");
        assertThat(ci.get("state").asText()).isEqualTo("HEALTHY");
        assertThat(ci.get("lastSuccessAt").isNull()).isFalse();
    }

    @Test
    void VYB0922_AC10_aRefusedCallRecordsNothingOnTheConnection() throws Exception {
        String clientId = unique("ci-other");
        serviceAccounts.create(unique("other"), "Without the scope", clientId, List.of(KnownServiceScopes.WEBHOOK_GIT), null);
        mvc.perform(post("/api/v1/ci/commits").with(jwt().jwt(j -> j.subject("svc").claim("azp", clientId))).contentType(JSON)
                .content(json.writeValueAsString(Map.of("sha", sha(), "message", "refused"))))
            .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT last_inbound_at FROM integration_connection WHERE key = 'git'", java.sql.Timestamp.class)).isNull();
        assertThat(healthOf("git").get("state").asText()).isEqualTo("NOT_CONNECTED");
    }
}
