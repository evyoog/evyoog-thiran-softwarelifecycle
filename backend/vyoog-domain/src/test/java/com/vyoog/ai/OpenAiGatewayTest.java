package com.vyoog.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * VYB-0936: {@link OpenAiGateway} against a real HTTP server on localhost that answers from a script. The clock
 * and the wait between attempts are the test's, so nothing sleeps; a timeout test really waits a few hundred ms.
 */
class OpenAiGatewayTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    record Reply(int status, String body, Map<String, String> headers, long delayMillis) {
        static Reply ok(String content) {
            return new Reply(200, "{\"choices\":[{\"message\":{\"content\":" + quote(content) + "},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}", Map.of(), 0);
        }
        static Reply status(int status) { return new Reply(status, "{\"error\":{\"message\":\"status " + status + " from the provider\"}}", Map.of(), 0); }
        static Reply status(int status, String retryAfter) { return new Reply(status, "{}", Map.of("Retry-After", retryAfter), 0); }
        static Reply slow(long millis) { return new Reply(200, "{}", Map.of(), millis); }
        static String quote(String s) { try { return JSON.writeValueAsString(s); } catch (Exception e) { throw new IllegalStateException(e); } }
    }

    private HttpServer server;
    private final List<Reply> script = new CopyOnWriteArrayList<>();
    private final AtomicInteger hits = new AtomicInteger();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> auth = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final GatewayCircuitBreakerTest.MutableClock clock = new GatewayCircuitBreakerTest.MutableClock();
    private final List<Duration> waits = new ArrayList<>();
    private String base;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            int n = hits.getAndIncrement();
            paths.add(ex.getRequestURI().getPath());
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Reply r = script.get(Math.min(n, script.size() - 1)); // the last reply repeats
            try {
                if (r.delayMillis() > 0) Thread.sleep(r.delayMillis());
                byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
                ex.sendResponseHeaders(r.status(), out.length);
                ex.getResponseBody().write(out);
            } catch (Exception ignored) {
                // the client gave up on a slow reply
            } finally {
                ex.close();
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool()); // a slow reply must not block the next request
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() { server.stop(0); }

    private OpenAiGateway gateway(boolean enabled, String key, Duration interactive, Duration batch, int threshold, Duration openFor) {
        return new OpenAiGateway(JSON, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), clock,
            d -> { waits.add(d); clock.advance(d); }, enabled, base + "/v1/chat/completions", base + "/v1/embeddings", key,
            "test-chat-model", "test-embedding-model", interactive, batch, threshold, openFor);
    }

    private OpenAiGateway gateway() {
        return gateway(true, "sk-test", Duration.ofSeconds(25), Duration.ofSeconds(300), 5, Duration.ofSeconds(60));
    }

    private static ChatRequest interactive() { return ChatRequest.interactive("be brief", "hello", 50, 0.2); }
    private static ChatRequest batch() { return ChatRequest.batchJson("be brief", "hello", 50, 0.2, Duration.ofSeconds(5)); }

    @Test
    void VYB0936_AC1_aChatCallSendsTheRequestAndReturnsTheReplyWithWhatTheProviderReported() throws Exception {
        script.add(Reply.ok("{\"a\":1}"));

        ChatReply reply = gateway().chat(batch());

        assertThat(reply.content()).isEqualTo("{\"a\":1}");
        assertThat(reply.model()).isEqualTo("test-chat-model");
        assertThat(reply.finishReason()).isEqualTo("stop");
        assertThat(reply.promptTokens()).isEqualTo(11);
        assertThat(reply.completionTokens()).isEqualTo(7);
        assertThat(paths).containsExactly("/v1/chat/completions");
        assertThat(auth).containsExactly("Bearer sk-test");
        JsonNode sent = JSON.readTree(bodies.get(0));
        assertThat(sent.path("model").asText()).isEqualTo("test-chat-model");
        assertThat(sent.at("/messages/0/role").asText()).isEqualTo("system");
        assertThat(sent.at("/messages/1/content").asText()).isEqualTo("hello");
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(50);
        assertThat(sent.at("/response_format/type").asText()).as("a JSON reply was asked for").isEqualTo("json_object");
    }

    @Test
    void VYB0936_AC1_aPlainTextRequestAsksForNoJsonMode() throws Exception {
        script.add(Reply.ok("fine"));

        gateway().chat(interactive());

        assertThat(JSON.readTree(bodies.get(0)).has("response_format")).isFalse();
    }

    @Test
    void VYB0936_AC1_whatTheProviderDoesNotReportStaysNullAndIsNotEstimated() {
        script.add(new Reply(200, "{\"choices\":[{\"message\":{\"content\":\"x\"}}]}", Map.of(), 0));

        ChatReply reply = gateway().chat(interactive());

        assertThat(reply.promptTokens()).isNull();
        assertThat(reply.completionTokens()).isNull();
    }

    @Test
    void VYB0936_AC2_whenNotConfiguredNothingIsSentAndTheReasonIsInWords() {
        script.add(Reply.ok("x"));

        for (OpenAiGateway off : List.of(
                gateway(false, "sk-test", Duration.ofSeconds(25), Duration.ofSeconds(300), 5, Duration.ofSeconds(60)),
                gateway(true, " ", Duration.ofSeconds(25), Duration.ofSeconds(300), 5, Duration.ofSeconds(60)),
                gateway(true, null, Duration.ofSeconds(25), Duration.ofSeconds(300), 5, Duration.ofSeconds(60)))) {
            assertThat(off.configured()).isFalse();
            assertThatThrownBy(() -> off.chat(interactive())).isInstanceOf(AiProviderUnavailableException.class)
                .hasMessageContaining("AI_ENABLED=true and AI_API_KEY");
            assertThatThrownBy(() -> off.embed("x", CallKind.INTERACTIVE)).isInstanceOf(AiProviderUnavailableException.class);
        }
        assertThat(hits).hasValue(0);
    }

    @Test
    void VYB0936_AC3_aRetryableStatusIsRetriedAndASecondAttemptThatWorksIsReturned() {
        script.add(Reply.status(503));
        script.add(Reply.ok("second time"));

        ChatReply reply = gateway().chat(interactive());

        assertThat(reply.content()).isEqualTo("second time");
        assertThat(hits).hasValue(2);
        assertThat(waits).hasSize(1);
        assertThat(waits.get(0)).isBetween(Duration.ofMillis(250), Duration.ofMillis(500));
    }

    @Test
    void VYB0936_AC3_everyStatusMeaningNotNowIsRetried() {
        for (int status : new int[] {408, 425, 429, 500, 502, 503, 504}) {
            hits.set(0);
            script.clear();
            script.add(Reply.status(status));
            script.add(Reply.ok("ok"));

            assertThat(gateway().chat(interactive()).content()).as("status " + status).isEqualTo("ok");
            assertThat(hits).as("status " + status).hasValue(2);
        }
    }

    @Test
    void VYB0936_AC3_aLongerRetryAfterIsHonouredUpToTheCap() {
        script.add(Reply.status(429, "2"));
        script.add(Reply.ok("ok"));

        gateway().chat(batch());

        assertThat(waits).hasSize(1);
        assertThat(waits.get(0)).as("Retry-After: 2 is longer than the 250-500 ms backoff").isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void VYB0936_AC4_aDefiniteAnswerIsNotRepeatedAndSaysWhatTheProviderSaid() {
        script.add(Reply.status(401));

        assertThatThrownBy(() -> gateway().chat(batch())).isInstanceOf(AiProviderUnavailableException.class)
            .hasMessageContaining("status 401 from the provider");

        assertThat(hits).as("one request, no retry").hasValue(1);
        assertThat(waits).isEmpty();
    }

    @Test
    void VYB0936_AC4_aDefiniteAnswerIsNotCountedAgainstTheProvidersHealth() {
        script.add(Reply.status(400));
        OpenAiGateway g = gateway(true, "sk-test", Duration.ofSeconds(25), Duration.ofSeconds(300), 2, Duration.ofSeconds(60));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> g.chat(interactive())).hasMessageContaining("status 400");
        }

        assertThat(g.breaker("chat").state()).isEqualTo(GatewayCircuitBreaker.State.CLOSED);
        assertThat(hits).hasValue(5);
    }

    @Test
    void VYB0936_AC5_aPersonWaitingGetsTwoAttemptsAndABackgroundJobGetsFour() {
        script.add(Reply.status(503));

        assertThatThrownBy(() -> gateway().chat(interactive())).isInstanceOf(AiProviderUnavailableException.class)
            .hasMessageContaining("after 2 attempts").hasMessageContaining("status 503");
        assertThat(hits).hasValue(2);

        hits.set(0);
        waits.clear();
        assertThatThrownBy(() -> gateway().chat(batch())).isInstanceOf(AiProviderUnavailableException.class)
            .hasMessageContaining("after 4 attempts");
        assertThat(hits).hasValue(4);
        assertThat(waits).as("a wait between attempts, none after the last").hasSize(3);
    }

    @Test
    void VYB0936_AC6_aRetryIsOnlyStartedIfItCanStillFinishInsideTheTotalLimit() {
        script.add(Reply.status(503));

        // a 1.5 second total limit leaves room for a second attempt but not a third
        assertThatThrownBy(() -> gateway(true, "sk-test", Duration.ofSeconds(25), Duration.ofMillis(1500), 5, Duration.ofSeconds(60)).chat(batch()))
            .hasMessageContaining("after 2 attempts");
        assertThat(hits).hasValue(2);

        // a limit shorter than the shortest wait plus a useful attempt: one attempt only
        hits.set(0);
        assertThatThrownBy(() -> gateway(true, "sk-test", Duration.ofSeconds(25), Duration.ofMillis(900), 5, Duration.ofSeconds(60)).chat(batch()))
            .hasMessageContaining("after 1 attempt:");
        assertThat(hits).hasValue(1);
    }

    @Test
    void VYB0936_AC7_aTimeoutIsAFailureThatIsRetriedAndThenReportedAsOne() {
        script.add(Reply.slow(1500));
        ChatRequest quick = new ChatRequest("s", "u", 10, 0.1, false, CallKind.INTERACTIVE, Duration.ofMillis(200));

        assertThatThrownBy(() -> gateway().chat(quick)).isInstanceOf(AiProviderUnavailableException.class)
            .hasMessageContaining("after 2 attempts").hasMessageContaining("could not reach the AI provider");
        assertThat(hits).hasValue(2);
    }

    @Test
    void VYB0936_AC7_aServerThatIsNotThereIsAFailureNotAnException() throws Exception {
        server.stop(0);

        assertThatThrownBy(() -> gateway().chat(interactive())).isInstanceOf(AiProviderUnavailableException.class)
            .hasMessageContaining("could not reach the AI provider");
    }

    @Test
    void VYB0936_AC8_afterRepeatedFailuresCallsAreRefusedWithoutBeingSentThenOneTrialRecoversIt() {
        script.add(Reply.status(503));
        script.add(Reply.status(503));
        script.add(Reply.status(503));
        script.add(Reply.status(503));
        script.add(Reply.ok("back"));
        OpenAiGateway g = gateway(true, "sk-test", Duration.ofSeconds(25), Duration.ofSeconds(300), 2, Duration.ofSeconds(60));

        assertThatThrownBy(() -> g.chat(interactive())).hasMessageContaining("after 2 attempts");
        assertThatThrownBy(() -> g.chat(interactive())).hasMessageContaining("after 2 attempts");
        int sentSoFar = hits.get();
        assertThat(g.breaker("chat").state()).isEqualTo(GatewayCircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> g.chat(interactive())).isInstanceOf(AiProviderUnavailableException.class)
            .hasMessageContaining("temporarily not being called").hasMessageContaining("seconds");
        assertThat(hits).as("nothing was sent while it is open").hasValue(sentSoFar);

        clock.advance(Duration.ofSeconds(61));
        assertThat(g.chat(interactive()).content()).as("the trial call").isEqualTo("back");
        assertThat(g.breaker("chat").state()).isEqualTo(GatewayCircuitBreaker.State.CLOSED);
    }

    @Test
    void VYB0936_AC8_anEmbeddingsOutageDoesNotStopChat() {
        script.add(Reply.status(503));
        OpenAiGateway g = gateway(true, "sk-test", Duration.ofSeconds(25), Duration.ofSeconds(300), 1, Duration.ofSeconds(60));
        assertThatThrownBy(() -> g.embed("text", CallKind.INTERACTIVE)).hasMessageContaining("after 2 attempts");
        assertThat(g.breaker("embeddings").state()).isEqualTo(GatewayCircuitBreaker.State.OPEN);
        assertThat(g.breaker("chat").state()).isEqualTo(GatewayCircuitBreaker.State.CLOSED);

        script.clear();
        script.add(Reply.ok("chat still works"));
        hits.set(0);
        assertThat(g.chat(interactive()).content()).isEqualTo("chat still works");
    }

    @Test
    void VYB0936_AC9_anEmbeddingIsReturnedAsReportedWithItsModelAndTokens() throws Exception {
        script.add(new Reply(200, "{\"data\":[{\"embedding\":[0.5,-1.25,2]}],\"usage\":{\"prompt_tokens\":4}}", Map.of(), 0));

        EmbeddingReply reply = gateway().embed("some text", CallKind.INTERACTIVE);

        assertThat(reply.vector()).containsExactly(0.5f, -1.25f, 2f);
        assertThat(reply.model()).isEqualTo("test-embedding-model");
        assertThat(reply.promptTokens()).isEqualTo(4);
        assertThat(paths).containsExactly("/v1/embeddings");
        JsonNode sent = JSON.readTree(bodies.get(0));
        assertThat(sent.path("input").asText()).isEqualTo("some text");
        assertThat(sent.path("model").asText()).isEqualTo("test-embedding-model");
    }

    @Test
    void VYB0936_AC10_aReplyThatCannotBeReadIsRefusedNotRetriedAndNotCountedAsAnOutage() {
        script.add(new Reply(200, "this is not json", Map.of(), 0));
        OpenAiGateway g = gateway(true, "sk-test", Duration.ofSeconds(25), Duration.ofSeconds(300), 1, Duration.ofSeconds(60));

        assertThatThrownBy(() -> g.chat(interactive())).isInstanceOf(AiProviderUnavailableException.class)
            .hasMessageContaining("could not be parsed");
        assertThat(hits).hasValue(1);
        assertThat(g.breaker("chat").state()).isEqualTo(GatewayCircuitBreaker.State.CLOSED);

        script.clear();
        script.add(new Reply(200, "{\"data\":[]}", Map.of(), 0));
        assertThatThrownBy(() -> g.embed("x", CallKind.INTERACTIVE)).hasMessageContaining("no embedding array");
        script.clear();
        script.add(new Reply(200, "{\"choices\":[]}", Map.of(), 0));
        assertThatThrownBy(() -> g.chat(interactive())).hasMessageContaining("no message content");
    }

    @Test
    void VYB0936_AC10_anUnreadableReplyDuringATrialDoesNotLeaveTheBreakerStuckHalfOpen() {
        script.add(Reply.status(503));
        OpenAiGateway g = gateway(true, "sk-test", Duration.ofSeconds(25), Duration.ofSeconds(300), 1, Duration.ofSeconds(60));
        assertThatThrownBy(() -> g.chat(interactive())).hasMessageContaining("after 2 attempts");
        clock.advance(Duration.ofSeconds(61));
        script.clear();
        script.add(new Reply(200, "garbage", Map.of(), 0));
        hits.set(0);

        assertThatThrownBy(() -> g.chat(interactive())).hasMessageContaining("could not be parsed");

        assertThat(g.breaker("chat").state()).isEqualTo(GatewayCircuitBreaker.State.CLOSED);
    }
}
