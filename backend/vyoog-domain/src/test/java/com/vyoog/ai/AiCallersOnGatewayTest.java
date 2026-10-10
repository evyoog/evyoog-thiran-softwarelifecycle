package com.vyoog.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * VYB-0936: each class that used to carry its own copy of the request block now asks {@link ModelGateway}, and
 * keeps what was its own: the prompt, the reading of the reply, and the refusal to invent an answer. The gateway
 * here is a fake, so these prove what each caller asks for and does with the answer, not the HTTP.
 */
class AiCallersOnGatewayTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ModelGateway gateway = mock(ModelGateway.class);

    @BeforeEach
    void configured() {
        when(gateway.configured()).thenReturn(true);
        when(gateway.chatModel()).thenReturn("test-chat-model");
        when(gateway.embeddingModel()).thenReturn("test-embedding-model");
    }

    private void replies(String content) {
        when(gateway.chat(any())).thenReturn(new ChatReply(content, "test-chat-model", "stop", 1, 1));
    }

    private ChatRequest sent() {
        ArgumentCaptor<ChatRequest> c = ArgumentCaptor.forClass(ChatRequest.class);
        verify(gateway).chat(c.capture());
        return c.getValue();
    }

    @Test
    void VYB0936_AC11_theRewriteAdvisorAsksAsAnInteractiveCallAndReadsTheSuggestion() {
        replies("{\"rewrittenStatement\":\"The system shall respond within 2 seconds.\",\"changes\":[\"Replaced 'fast' with a bound.\"]}");
        var advisor = new OpenAiRequirementRewriteAdvisor(gateway, JSON);

        var suggestion = advisor.suggest("The system shall be fast.", Map.of("wording", -10));

        assertThat(suggestion.rewrittenStatement()).isEqualTo("The system shall respond within 2 seconds.");
        assertThat(suggestion.changes()).containsExactly("Replaced 'fast' with a bound.");
        assertThat(advisor.modelName()).isEqualTo("test-chat-model");
        ChatRequest r = sent();
        assertThat(r.kind()).isEqualTo(CallKind.INTERACTIVE);
        assertThat(r.maxTokens()).isEqualTo(400);
        assertThat(r.user()).contains("The system shall be fast.").contains("wording");
    }

    @Test
    void VYB0936_AC11_theRewriteAdvisorStillRefusesRatherThanInventAnAnswer() {
        var advisor = new OpenAiRequirementRewriteAdvisor(gateway, JSON);

        replies("{\"changes\":[]}");
        assertThatThrownBy(() -> advisor.suggest("x", Map.of())).isInstanceOf(AiProviderUnavailableException.class)
            .hasMessageContaining("did not return a rewritten statement");

        replies("not json");
        assertThatThrownBy(() -> advisor.suggest("x", Map.of())).hasMessageContaining("could not be parsed");

        when(gateway.configured()).thenReturn(false);
        assertThatThrownBy(() -> advisor.suggest("x", Map.of())).hasMessageContaining("not configured");

        when(gateway.configured()).thenReturn(true);
        assertThatThrownBy(() -> advisor.suggest("  ", Map.of())).hasMessageContaining("empty");
    }

    @Test
    void VYB0936_AC11_aGatewayRefusalReachesTheCallerAsTheSameException() {
        when(gateway.chat(any())).thenThrow(new AiProviderUnavailableException("The AI provider is temporarily not being called"));
        var advisor = new OpenAiRequirementRewriteAdvisor(gateway, JSON);

        assertThatThrownBy(() -> advisor.suggest("x", Map.of())).isInstanceOf(AiProviderUnavailableException.class)
            .hasMessageContaining("temporarily");
    }

    @Test
    void VYB0936_AC12_theTraceClassifierAsksInteractivelyAndDropsKeysItWasNotOfferedAndTypesThatDoNotExist() {
        replies("{\"links\":[{\"key\":\"VY-1\",\"linkType\":\"SATISFIES\",\"rationale\":\"r\"},"
            + "{\"key\":\"VY-99\",\"linkType\":\"SATISFIES\",\"rationale\":\"not offered\"},"
            + "{\"key\":\"VY-2\",\"linkType\":\"MADE_UP\",\"rationale\":\"not a type\"}]}");
        var classifier = new OpenAiTraceRelationClassifier(gateway, JSON);

        var links = classifier.classify("new text", List.of(
            new TraceRelationClassifier.Candidate("VY-1", "one"), new TraceRelationClassifier.Candidate("VY-2", "two")));

        assertThat(links).extracting(TraceRelationClassifier.ProposedLink::key).containsExactly("VY-1");
        assertThat(sent().kind()).isEqualTo(CallKind.INTERACTIVE);
    }

    @Test
    void VYB0936_AC12_theTraceClassifierSendsNothingWhenThereIsNothingToCompare() {
        assertThat(new OpenAiTraceRelationClassifier(gateway, JSON).classify("text", List.of())).isEmpty();

        verify(gateway, never()).chat(any());
    }

    @Test
    void VYB0936_AC13_theAdjudicatorIsABatchCallAndReadsTheVerdict() {
        replies("{\"contradicts\":true,\"confidence\":0.9,\"explanation\":\"A says on, B says off\"}");
        var adjudicator = new OpenAiLlmAdjudicator(gateway, JSON);

        var verdict = adjudicator.adjudicate("It is on.", "It is off.");

        assertThat(verdict.contradicts()).isTrue();
        assertThat(verdict.confidence()).isEqualTo(0.9);
        assertThat(adjudicator.isConfigured()).isTrue();
        assertThat(adjudicator.modelAndPromptVersion()).isEqualTo("test-chat-model/conflict-adjudication-v1");
        assertThat(sent().kind()).isEqualTo(CallKind.BATCH);
    }

    @Test
    void VYB0936_AC13_theAdjudicatorRefusesAVerdictItWasNotGiven() {
        var adjudicator = new OpenAiLlmAdjudicator(gateway, JSON);

        replies("{\"confidence\":0.5}");
        assertThatThrownBy(() -> adjudicator.adjudicate("a", "b")).hasMessageContaining("missing a 'contradicts' verdict");

        when(gateway.configured()).thenReturn(false);
        assertThat(adjudicator.isConfigured()).isFalse();
        assertThatThrownBy(() -> adjudicator.adjudicate("a", "b")).hasMessageContaining("not configured");
    }

    @Test
    void VYB0936_AC14_theEmbeddingProviderAsksInterativelyAndRefusesAVectorOfTheWrongWidth() {
        float[] good = new float[1536];
        good[3] = 0.25f;
        when(gateway.embed(eq("hello"), eq(CallKind.INTERACTIVE))).thenReturn(new EmbeddingReply(good, "m", 3));
        var provider = new OpenAiEmbeddingProvider(gateway);

        assertThat(provider.embed("hello")).hasSize(1536).contains(0.25f);
        assertThat(provider.modelName()).isEqualTo("test-embedding-model");
        assertThat(provider.dimensions()).isEqualTo(1536);

        when(gateway.embed(eq("short"), any())).thenReturn(new EmbeddingReply(new float[3], "m", null));
        assertThatThrownBy(() -> provider.embed("short")).isInstanceOf(AiProviderUnavailableException.class)
            .hasMessageContaining("3-dimension").hasMessageContaining("expected 1536");

        when(gateway.configured()).thenReturn(false);
        assertThatThrownBy(() -> provider.embed("hello")).hasMessageContaining("not configured");
    }

    @Test
    void VYB0936_AC15_theJsonClientAsksForABatchJsonReplyWithTheAnalysisTimeoutAndParsesIt() {
        replies("{\"findings\":[1,2]}");
        var client = new JsonModelClient(gateway, JSON, 90);

        assertThat(client.completeJson("test", "sys", "usr", 900, 0.2).path("findings")).hasSize(2);
        assertThat(client.model()).isEqualTo("test-chat-model");
        assertThat(client.configured()).isTrue();
        ChatRequest r = sent();
        assertThat(r.kind()).isEqualTo(CallKind.BATCH);
        assertThat(r.jsonObject()).isTrue();
        assertThat(r.timeout().toSeconds()).isEqualTo(90);
        assertThat(r.maxTokens()).isEqualTo(900);
    }

    @Test
    void VYB0936_AC15_theJsonClientRefusesAReplyCutOffAtTheTokenLimitAndOneThatIsNotJson() {
        var client = new JsonModelClient(gateway, JSON, 90);

        when(gateway.chat(any())).thenReturn(new ChatReply("{\"findings\":[", "m", "length", 1, 1));
        assertThatThrownBy(() -> client.completeJson("test", "s", "u", 10, 0.1)).isInstanceOf(AiProviderUnavailableException.class)
            .hasMessageContaining("cut off at the token limit");

        replies("<html>");
        assertThatThrownBy(() -> client.completeJson("test", "s", "u", 10, 0.1)).hasMessageContaining("could not be parsed");
    }
}
