package com.vyoog.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vyoog.platform.tx.NetworkCallGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** VYB-0939: the metering layer in front of the redacting gateway; the provider, budget and ledger are fakes. */
class MeteredModelGatewayTest {

    private final ModelGateway delegate = mock(ModelGateway.class);
    private final AiBudgetService budget = mock(AiBudgetService.class);
    private final AiUsageLedger ledger = mock(AiUsageLedger.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private MeteredModelGateway gateway;

    private static final ChatRequest REQUEST = ChatRequest.interactive("rewrite-suggestion", "You rewrite requirements.", "text", 100, 0.1);

    @BeforeEach
    void wire() {
        when(delegate.configured()).thenReturn(true);
        when(delegate.chatModel()).thenReturn("chat-model");
        when(delegate.embeddingModel()).thenReturn("embedding-model");
        gateway = new MeteredModelGateway(delegate, budget, ledger, meters, NetworkCallGuard.refusing());
    }

    private AiUsageLedger.Entry recorded() {
        ArgumentCaptor<AiUsageLedger.Entry> c = ArgumentCaptor.forClass(AiUsageLedger.Entry.class);
        verify(ledger).record(c.capture());
        return c.getValue();
    }

    @Test
    void VYB0939_AC1_aSuccessfulChatCallIsRecordedWithPurposePromptVersionModelAndTheTokensTheProviderReported() {
        when(delegate.chat(REQUEST)).thenReturn(new ChatReply("{}", "chat-model-2026", "stop", 9, 6));

        ChatReply reply = gateway.chat(REQUEST);

        assertThat(reply.content()).isEqualTo("{}");
        AiUsageLedger.Entry e = recorded();
        assertThat(e.purpose()).isEqualTo("rewrite-suggestion");
        assertThat(e.promptVersion()).isEqualTo(REQUEST.promptVersion()).hasSize(8);
        assertThat(e.model()).as("the model the provider says it used").isEqualTo("chat-model-2026");
        assertThat(e.endpoint()).isEqualTo("CHAT");
        assertThat(e.kind()).isEqualTo(CallKind.INTERACTIVE);
        assertThat(e.outcome()).isEqualTo(AiUsageLedger.Outcome.OK);
        assertThat(e.promptTokens()).isEqualTo(9);
        assertThat(e.completionTokens()).isEqualTo(6);
        assertThat(e.totalTokens()).isEqualTo(15);
        verify(budget).recordUsed(15);
    }

    @Test
    void VYB0939_AC1_aReplyThatReportsNoTokensIsRecordedWithNullNotAGuess() {
        when(delegate.chat(REQUEST)).thenReturn(new ChatReply("{}", null, "stop", null, null));

        gateway.chat(REQUEST);

        AiUsageLedger.Entry e = recorded();
        assertThat(e.totalTokens()).isNull();
        assertThat(e.promptTokens()).isNull();
        assertThat(e.model()).as("falls back to the configured model when the reply names none").isEqualTo("chat-model");
        verify(budget, never()).recordUsed(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void VYB0939_AC2_anEmbeddingCallIsRecordedUnderItsOwnPurposeWithNoPromptVersion() {
        when(delegate.embed("some text", CallKind.BATCH)).thenReturn(new EmbeddingReply(new float[] {1f}, "embedding-model", 5));

        gateway.embed("some text", CallKind.BATCH);

        AiUsageLedger.Entry e = recorded();
        assertThat(e.purpose()).isEqualTo("embedding");
        assertThat(e.promptVersion()).isEqualTo("n/a");
        assertThat(e.endpoint()).isEqualTo("EMBEDDINGS");
        assertThat(e.kind()).isEqualTo(CallKind.BATCH);
        assertThat(e.totalTokens()).isEqualTo(5);
    }

    @Test
    void VYB0939_AC3_aFailedCallIsRecordedAsFailedWithNoTokensAndTheFailureStillReachesTheCaller() {
        AiProviderUnavailableException failure = new AiProviderUnavailableException("the provider is down");
        when(delegate.chat(REQUEST)).thenThrow(failure);

        assertThatThrownBy(() -> gateway.chat(REQUEST)).isSameAs(failure);

        AiUsageLedger.Entry e = recorded();
        assertThat(e.outcome()).isEqualTo(AiUsageLedger.Outcome.FAILED);
        assertThat(e.totalTokens()).isNull();
        verify(budget, never()).recordUsed(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void VYB0939_AC4_overBudgetTheCallIsRefusedWithTheReasonAndNothingIsSentToTheProvider() {
        AiProviderUnavailableException reason = new AiProviderUnavailableException("The AI token budget for today is used up");
        doThrow(reason).when(budget).requireWithinBudget();

        assertThatThrownBy(() -> gateway.chat(REQUEST)).isSameAs(reason);
        assertThatThrownBy(() -> gateway.embed("x", CallKind.INTERACTIVE)).isSameAs(reason);

        verify(delegate, never()).chat(any());
        verify(delegate, never()).embed(any(), any());
        ArgumentCaptor<AiUsageLedger.Entry> c = ArgumentCaptor.forClass(AiUsageLedger.Entry.class);
        verify(ledger, org.mockito.Mockito.times(2)).record(c.capture());
        assertThat(c.getAllValues()).allSatisfy(e -> assertThat(e.outcome()).isEqualTo(AiUsageLedger.Outcome.BUDGET_REFUSED));
        assertThat(meters.get("ai.budget.refusals").tag("purpose", "rewrite-suggestion").counter().count()).isEqualTo(1.0);
    }

    @Test
    void VYB0939_AC5_whenAiIsNotConfiguredNothingIsCheckedRecordedOrCounted() {
        when(delegate.configured()).thenReturn(false);
        AiProviderUnavailableException off = new AiProviderUnavailableException("AI is switched off");
        when(delegate.chat(REQUEST)).thenThrow(off);

        assertThatThrownBy(() -> gateway.chat(REQUEST)).isSameAs(off);

        verifyNoInteractions(budget, ledger);
        assertThat(meters.getMeters()).isEmpty();
    }

    @Test
    void VYB0939_AC6_successfulCallsAreCountedByPurposeAndTheirTokensToo() {
        when(delegate.chat(REQUEST)).thenReturn(new ChatReply("{}", "m", "stop", 9, 6));

        gateway.chat(REQUEST);
        gateway.chat(REQUEST);

        assertThat(meters.get("ai.calls").tags("purpose", "rewrite-suggestion", "outcome", "OK").counter().count()).isEqualTo(2.0);
        assertThat(meters.get("ai.tokens").tag("purpose", "rewrite-suggestion").counter().count()).isEqualTo(30.0);
    }

    @Test
    void VYB0939_AC7_theModelAndEmbeddingModelAreThoseOfTheGatewayItWraps() {
        assertThat(gateway.chatModel()).isEqualTo("chat-model");
        assertThat(gateway.embeddingModel()).isEqualTo("embedding-model");
        assertThat(gateway.configured()).isTrue();
    }

    @Test
    void VYB0939_AC8_theTimeASlowCallTookIsRecordedInMilliseconds() {
        when(delegate.chat(REQUEST)).thenAnswer(i -> {
            Thread.sleep(Duration.ofMillis(30).toMillis());
            return new ChatReply("{}", "m", "stop", 1, 1);
        });

        gateway.chat(REQUEST);

        assertThat(recorded().durationMillis()).isGreaterThanOrEqualTo(25);
    }
}
