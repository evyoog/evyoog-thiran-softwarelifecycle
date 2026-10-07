package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import com.vyoog.ai.EmbeddingProvider;
import com.vyoog.ai.KnownPeople;
import com.vyoog.ai.RedactingModelGateway;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.ScopeType;
import com.vyoog.ai.JsonModelClient;
import com.vyoog.ai.ModelGateway;
import com.vyoog.ai.OpenAiEmbeddingProvider;
import com.vyoog.ai.OpenAiGateway;
import com.vyoog.ai.RequirementRewriteAdvisor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * VYB-0936 (F28): the real application wiring with AI switched on, against a stub provider in this JVM. It proves
 * what the unit tests cannot: Spring builds exactly one gateway from the {@code vyoog.ai.*} configuration, and
 * the classes that used to carry their own HTTP code (embeddings, rewrite suggestions, the JSON client) are
 * handed that gateway and reach the provider through it, over a real socket.
 */
@AutoConfigureMockMvc
class ModelGatewayWiringIT extends IntegrationTestBase {

    private static final HttpServer STUB;
    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    /** The full body of every request the stub provider received, in order. */
    private static final List<String> BODIES = new CopyOnWriteArrayList<>();

    static {
        try {
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        STUB.createContext("/v1/embeddings", ex -> {
            REQUESTS.add("embeddings Authorization=" + ex.getRequestHeaders().getFirst("Authorization"));
            BODIES.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            StringBuilder vector = new StringBuilder("[");
            for (int i = 0; i < 1536; i++) vector.append(i == 0 ? "" : ",").append(i == 0 ? "0.5" : "0");
            byte[] out = ("{\"data\":[{\"embedding\":" + vector + "]}],\"usage\":{\"prompt_tokens\":5}}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        STUB.createContext("/v1/chat/completions", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            BODIES.add(body);
            REQUESTS.add("chat max_tokens=" + (body.contains("\"max_tokens\":400") ? "400" : "other")
                + " json=" + body.contains("json_object"));
            // a request that carries tokens gets a reply that uses them, as a model told to keep them would
            String statement = body.contains("[PERSON_1]") ? "[PERSON_1] can be reached at [EMAIL_1]" : "The system shall answer in 2 s.";
            byte[] out = ("{\"choices\":[{\"message\":{\"content\":\"{\\\"rewrittenStatement\\\":\\\"" + statement + "\\\","
                + "\\\"changes\\\":[\\\"bounded it\\\"]}\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":6}}")
                .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        STUB.start();
    }

    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + STUB.getAddress().getPort();
        r.add("vyoog.ai.enabled", () -> "true");
        r.add("vyoog.ai.api-key", () -> "it-key");
        r.add("vyoog.ai.api-url", () -> base + "/v1/chat/completions");
        r.add("vyoog.ai.embeddings-url", () -> base + "/v1/embeddings");
    }

    @AfterAll
    static void stop() { STUB.stop(0); }

    @Autowired ApplicationContext context;
    @Autowired ModelGateway gateway;
    @Autowired EmbeddingProvider embeddings;
    @Autowired RequirementRewriteAdvisor rewrite;
    @Autowired JsonModelClient jsonClient;
    @Autowired MockMvc mvc;
    @Autowired KnownPeople knownPeople;
    @Autowired com.vyoog.ai.RedactionSettings redactionSettings;

    @Test
    void VYB0936_AC16_springBuildsOneProviderGatewayAndEveryoneIsHandedTheRedactingOneInFrontOfIt() {
        assertThat(context.getBeansOfType(ModelGateway.class)).as("the provider and the redacting front for it").hasSize(2);
        assertThat(context.getBean("openAiGateway")).isInstanceOf(OpenAiGateway.class);
        assertThat(gateway).as("what is injected everywhere").isInstanceOf(RedactingModelGateway.class);
        assertThat(gateway.configured()).isTrue();
        assertThat(gateway.chatModel()).isEqualTo("gpt-4o-mini");
        assertThat(gateway.embeddingModel()).isEqualTo("text-embedding-3-small");
    }

    @Test
    void VYB0936_AC16_embeddingsReachTheProviderThroughTheGatewayWithTheConfiguredKey() {
        assertThat(embeddings).isInstanceOf(OpenAiEmbeddingProvider.class);

        float[] vector = embeddings.embed("The system shall encrypt data at rest.");

        assertThat(vector).hasSize(1536);
        assertThat(vector[0]).isEqualTo(0.5f);
        assertThat(REQUESTS).contains("embeddings Authorization=Bearer it-key");
    }

    @Test
    void VYB0936_AC16_aRewriteSuggestionAndAJsonCallGoThroughTheSameGateway() {
        var suggestion = rewrite.suggest("The system shall be fast.", Map.of("wording", -10));
        assertThat(suggestion.rewrittenStatement()).isEqualTo("The system shall answer in 2 s.");
        assertThat(suggestion.changes()).containsExactly("bounded it");
        assertThat(REQUESTS).contains("chat max_tokens=400 json=false");

        var json = jsonClient.completeJson("sys", "usr", 900, 0.2);
        assertThat(json.path("rewrittenStatement").asText()).isEqualTo("The system shall answer in 2 s.");
        assertThat(REQUESTS).contains("chat max_tokens=other json=true");
    }

    // ------------------------------------------------------------------ VYB-0937: redaction, end to end

    private static final String SECRET = "AKIAIOSFODNN7EXAMPLE";

    private String lastChatBody() {
        for (int i = BODIES.size() - 1; i >= 0; i--) if (BODIES.get(i).contains("\"messages\"")) return BODIES.get(i);
        throw new IllegalStateException("the stub provider has seen no chat request");
    }

    private UUID aPerson(String displayName) {
        String id = unique("p");
        UUID user = users.upsert("sub-" + id, id + "@it.test", displayName).getId();
        knownPeople.forget(); // a person added a moment ago is matched from now on
        return user;
    }

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

    @Test
    void VYB0937_AC16_whatLeavesTheSystemHasNoSecretsNoPersonalDataAndTheReplyComesBackWithThemRestored() {
        String name = "Quillon" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).replaceAll("[0-9]", "x") + " Fenwright";
        aPerson(name);
        String email = unique("quill") + "@corp.example";

        var suggestion = rewrite.suggest("Ask " + name + " at " + email + " using key " + SECRET + " from 10.20.30.40", Map.of("wording", -10));

        String sent = lastChatBody();
        assertThat(sent).doesNotContain(name).doesNotContain(email).doesNotContain(SECRET).doesNotContain("10.20.30.40");
        assertThat(sent).contains("[PERSON_1]").contains("[EMAIL_1]").contains("[IP_1]").contains("[REDACTED-SECRET]");
        assertThat(suggestion.rewrittenStatement()).as("the provider's tokens are put back").isEqualTo(name + " can be reached at " + email);
    }

    @Test
    void VYB0937_AC17_anEmbeddingIsRedactedToo() {
        String email = unique("emb") + "@corp.example";
        int before = BODIES.size();

        embeddings.embed("Contact " + email + " with " + SECRET);

        String sent = BODIES.get(BODIES.size() - 1);
        assertThat(BODIES.size()).isGreaterThan(before);
        assertThat(sent).doesNotContain(email).doesNotContain(SECRET).contains("[EMAIL_1]").contains("[REDACTED-SECRET]");
    }

    @Test
    void VYB0937_AC18_anAdministratorSwitchesAClassOffOverHttpThenItIsSentAsItIsAndSecretsStillGoNoMatterWhat() throws Exception {
        String email = unique("opt") + "@corp.example";
        try {
            mvc.perform(put("/api/v1/settings/ai-redaction").with(anAdministrator()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"disabled\":[\"EMAIL\",\"PERSON\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.disabled[0]").value("EMAIL")).andExpect(jsonPath("$.disabled[1]").value("PERSON"));

            rewrite.suggest("Mail " + email + " key " + SECRET, Map.of());

            assertThat(lastChatBody()).contains(email).doesNotContain(SECRET).contains("[REDACTED-SECRET]");
            mvc.perform(get("/api/v1/settings").with(anAdministrator())).andExpect(status().isOk())
                .andExpect(jsonPath("$.aiRedactionDisabled[0]").value("EMAIL"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'settings.ai-redaction-changed' AND after->'disabled' @> '[\"EMAIL\"]'::jsonb", Integer.class))
                .as("the change was audited, with before and after").isGreaterThanOrEqualTo(1);
        } finally {
            redactionSettings.setDisabled(List.of(), null);
        }

        rewrite.suggest("Mail " + email, Map.of());
        assertThat(lastChatBody()).as("switched back on").doesNotContain(email).contains("[EMAIL_1]");
    }

    @Test
    void VYB0937_AC19_onlyAnAdministratorCanChangeItAndSecretsCannotBeSwitchedOffByAnyRoute() throws Exception {
        String body = "{\"disabled\":[\"PHONE\"]}";
        mvc.perform(put("/api/v1/settings/ai-redaction").with(anOrdinaryUser()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/settings/ai-redaction").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());

        JwtRequestPostProcessor admin = anAdministrator();
        mvc.perform(put("/api/v1/settings/ai-redaction").with(admin).contentType(MediaType.APPLICATION_JSON).content("{\"disabled\":[\"SECRET\"]}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/settings/ai-redaction").with(admin).contentType(MediaType.APPLICATION_JSON).content("{\"disabled\":[\"NONSENSE\"]}")).andExpect(status().isBadRequest());
        assertThat(redactionSettings.disabled()).as("a refused change changes nothing").isEmpty();

        assertThatThrownBy(() -> jdbc.update("UPDATE app_config SET ai_redaction_disabled = ARRAY['SECRET'] WHERE id = 1"))
            .as("the database refuses it for any writer, not only the API").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE app_config SET ai_redaction_disabled = ARRAY['EMAIL','BOGUS'] WHERE id = 1"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(redactionSettings.disabled()).isEmpty();
    }
}
