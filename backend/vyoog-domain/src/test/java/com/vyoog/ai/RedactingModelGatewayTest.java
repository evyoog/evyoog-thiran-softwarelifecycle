package com.vyoog.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * VYB-0937: the gateway everything is handed. The provider is a fake that records what it was sent, so these prove
 * what leaves the system, what comes back, and that a failure to check sends nothing.
 */
class RedactingModelGatewayTest {

    private final ModelGateway provider = mock(ModelGateway.class);
    private final RedactionSettings settings = mock(RedactionSettings.class);
    private final KnownPeople people = mock(KnownPeople.class);
    private final Redactor redactor = new Redactor();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private RedactingModelGateway gateway;

    @BeforeEach
    void wire() {
        when(provider.configured()).thenReturn(true);
        when(provider.chatModel()).thenReturn("chat-model");
        when(provider.embeddingModel()).thenReturn("embedding-model");
        when(settings.disabled()).thenReturn(EnumSet.noneOf(DataClass.class));
        when(people.names()).thenReturn(redactor.nameMatcher(List.of("Zelda Quasar")));
        gateway = new RedactingModelGateway(provider, redactor, settings, people, meters);
    }

    private ChatReply replies(String content) {
        ChatReply reply = new ChatReply(content, "chat-model", "stop", 5, 6);
        when(provider.chat(any())).thenReturn(reply);
        return reply;
    }

    private ChatRequest sent() {
        ArgumentCaptor<ChatRequest> c = ArgumentCaptor.forClass(ChatRequest.class);
        verify(provider).chat(c.capture());
        return c.getValue();
    }

    @Test
    void VYB0937_AC8_whatIsSentHasNoSecretsAndNoPersonalDataAndTheSystemPromptIsUntouched() {
        replies("{}");

        gateway.chat(ChatRequest.interactive("test", "You review text for Zelda Quasar's team.", "Zelda Quasar (zelda@corp.example, +44 20 7946 0958) uses key AKIAIOSFODNN7EXAMPLE from 10.0.0.7", 100, 0.1));

        ChatRequest r = sent();
        assertThat(r.user()).isEqualTo("[PERSON_1] ([EMAIL_1], [PHONE_1]) uses key " + Redactor.SECRET_MARKER + " from [IP_1]");
        assertThat(r.system()).as("the caller's fixed instruction is not redacted").isEqualTo("You review text for Zelda Quasar's team.");
        assertThat(r.maxTokens()).isEqualTo(100);
        assertThat(r.kind()).isEqualTo(CallKind.INTERACTIVE);
    }

    @Test
    void VYB0937_AC9_theReplyComesBackWithTheOriginalValuesPutBackAndTheMetadataIntact() {
        replies("{\"owner\":\"[PERSON_1]\",\"contact\":\"[EMAIL_1]\"}");

        ChatReply reply = gateway.chat(ChatRequest.batchJson("test", "sys", "Zelda Quasar zelda@corp.example", 50, 0.1, java.time.Duration.ofSeconds(5)));

        assertThat(reply.content()).isEqualTo("{\"owner\":\"Zelda Quasar\",\"contact\":\"zelda@corp.example\"}");
        assertThat(reply.model()).isEqualTo("chat-model");
        assertThat(reply.promptTokens()).isEqualTo(5);
        assertThat(reply.completionTokens()).isEqualTo(6);
    }

    @Test
    void VYB0937_AC9_aJsonShapedReplyFromACallNotFlaggedAsJsonIsStillRestoredAsJson() throws Exception {
        when(people.names()).thenReturn(redactor.nameMatcher(List.of("Zelda \"Z\" Quasar")));
        replies("{\"who\":\"[PERSON_1]\"}");

        ChatReply reply = gateway.chat(ChatRequest.interactive("test", "s", "Ask Zelda \"Z\" Quasar", 50, 0.1)); // jsonObject=false

        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(reply.content()).path("who").asText())
            .as("the quote in the name was escaped, so the document the caller parses is valid").isEqualTo("Zelda \"Z\" Quasar");
    }

    @Test
    void VYB0937_AC9_aSecretIsNeverPutBackEvenIfTheProviderEchoesTheMarker() {
        replies("I removed " + Redactor.SECRET_MARKER);

        ChatReply reply = gateway.chat(ChatRequest.interactive("test", "s", "key AKIAIOSFODNN7EXAMPLE", 50, 0.1));

        assertThat(reply.content()).isEqualTo("I removed " + Redactor.SECRET_MARKER).doesNotContain("AKIA");
    }

    @Test
    void VYB0937_AC10_textWithNothingToRedactIsSentExactlyAsGiven() {
        replies("ok");
        ChatRequest original = ChatRequest.interactive("test", "s", "The system shall respond within 2 seconds.", 50, 0.1);

        gateway.chat(original);

        assertThat(sent()).isSameAs(original);
        assertThat(meters.find("ai.redactions").counters()).isEmpty();
    }

    @Test
    void VYB0937_AC11_anEmbeddingIsRedactedBeforeItIsSentAndHasNothingToRestore() {
        when(provider.embed(anyString(), any())).thenReturn(new EmbeddingReply(new float[] {1f}, "embedding-model", 3));

        EmbeddingReply reply = gateway.embed("Zelda Quasar wrote to zelda@corp.example with AKIAIOSFODNN7EXAMPLE", CallKind.INTERACTIVE);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(provider).embed(text.capture(), eq(CallKind.INTERACTIVE));
        assertThat(text.getValue()).isEqualTo("[PERSON_1] wrote to [EMAIL_1] with " + Redactor.SECRET_MARKER);
        assertThat(reply.vector()).containsExactly(1f);
    }

    @Test
    void VYB0937_AC12_aClassTheAdministratorSwitchedOffIsSentAsItIsAndTheOthersStillGo() {
        when(settings.disabled()).thenReturn(EnumSet.of(DataClass.EMAIL, DataClass.PERSON));
        replies("{}");

        gateway.chat(ChatRequest.interactive("test", "s", "Zelda Quasar zelda@corp.example call +44 20 7946 0958 key AKIAIOSFODNN7EXAMPLE", 50, 0.1));

        assertThat(sent().user()).isEqualTo("Zelda Quasar zelda@corp.example call [PHONE_1] key " + Redactor.SECRET_MARKER);
        verify(people, never()).names(); // switched off: the list of people is not even loaded
    }

    @Test
    void VYB0937_AC13_ifTheTextCannotBeCheckedNothingIsSentAndTheCallIsRefused() {
        when(settings.disabled()).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> gateway.chat(ChatRequest.interactive("test", "s", "anything", 50, 0.1))).isInstanceOf(AiProviderUnavailableException.class)
            .hasMessageContaining("Nothing was sent");
        assertThatThrownBy(() -> gateway.embed("anything", CallKind.BATCH)).isInstanceOf(AiProviderUnavailableException.class);

        verify(provider, never()).chat(any());
        verify(provider, never()).embed(any(), any());
    }

    @Test
    void VYB0937_AC13_aFailureLoadingThePeopleAlsoSendsNothing() {
        when(people.names()).thenThrow(new IllegalStateException("user table unreadable"));

        assertThatThrownBy(() -> gateway.chat(ChatRequest.interactive("test", "s", "anything", 50, 0.1))).isInstanceOf(AiProviderUnavailableException.class);

        verify(provider, never()).chat(any());
    }

    @Test
    void VYB0937_AC14_aProviderFailureStillReachesTheCallerAsTheSameException() {
        when(provider.chat(any())).thenThrow(new AiProviderUnavailableException("temporarily not being called"));

        assertThatThrownBy(() -> gateway.chat(ChatRequest.interactive("test", "s", "zelda@corp.example", 50, 0.1)))
            .isInstanceOf(AiProviderUnavailableException.class).hasMessageContaining("temporarily");
    }

    @Test
    void VYB0937_AC14_whenAiIsNotConfiguredTheProviderRefusesAndNothingIsDoneFirst() {
        when(provider.configured()).thenReturn(false);
        when(provider.chat(any())).thenThrow(new AiProviderUnavailableException("AI is not configured"));

        assertThat(gateway.configured()).isFalse();
        assertThatThrownBy(() -> gateway.chat(ChatRequest.interactive("test", "s", "x", 5, 0.1))).hasMessageContaining("not configured");
        verify(settings, never()).disabled();
    }

    @Test
    void VYB0937_AC15_whatWasRedactedIsCountedByClassAndNeverTheValues() {
        replies("{}");

        gateway.chat(ChatRequest.interactive("test", "s", "a@b.example and c@d.example and key AKIAIOSFODNN7EXAMPLE", 50, 0.1));

        assertThat(meters.get("ai.redactions").tag("class", "EMAIL").tag("endpoint", "chat").counter().count()).isEqualTo(2.0);
        assertThat(meters.get("ai.redactions").tag("class", "SECRET").tag("endpoint", "chat").counter().count()).isEqualTo(1.0);
        meters.getMeters().forEach(m -> m.getId().getTags().forEach(t -> assertThat(t.getValue()).doesNotContain("@").doesNotContain("AKIA")));
    }

    @Test
    void VYB0937_AC15_modelNamesAndConfiguredPassThrough() {
        assertThat(gateway.chatModel()).isEqualTo("chat-model");
        assertThat(gateway.embeddingModel()).isEqualTo("embedding-model");
        assertThat(gateway.configured()).isTrue();
    }
}
