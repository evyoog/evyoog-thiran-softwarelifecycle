package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.vyoog.ai.EmbeddingProvider;
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
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * VYB-0936 (F28): the real application wiring with AI switched on, against a stub provider in this JVM. It proves
 * what the unit tests cannot: Spring builds exactly one gateway from the {@code vyoog.ai.*} configuration, and
 * the classes that used to carry their own HTTP code (embeddings, rewrite suggestions, the JSON client) are
 * handed that gateway and reach the provider through it, over a real socket.
 */
class ModelGatewayWiringIT extends IntegrationTestBase {

    private static final HttpServer STUB;
    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();

    static {
        try {
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        STUB.createContext("/v1/embeddings", ex -> {
            REQUESTS.add("embeddings Authorization=" + ex.getRequestHeaders().getFirst("Authorization"));
            ex.getRequestBody().readAllBytes();
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
            REQUESTS.add("chat max_tokens=" + (body.contains("\"max_tokens\":400") ? "400" : "other")
                + " json=" + body.contains("json_object"));
            byte[] out = ("{\"choices\":[{\"message\":{\"content\":\"{\\\"rewrittenStatement\\\":\\\"The system shall answer in 2 s.\\\","
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

    @Test
    void VYB0936_AC16_springBuildsExactlyOneGatewayFromTheConfiguration() {
        assertThat(context.getBeansOfType(ModelGateway.class)).hasSize(1);
        assertThat(gateway).isInstanceOf(OpenAiGateway.class);
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
}
