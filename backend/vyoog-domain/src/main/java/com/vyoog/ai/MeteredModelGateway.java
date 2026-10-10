package com.vyoog.ai;

import com.vyoog.platform.tx.NetworkCallGuard;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * VYB-0939 (F29, F30): the {@link ModelGateway} everything is handed. In front of the redacting gateway it
 * <ol>
 *   <li><b>refuses a call made inside a database transaction</b> in tests and logs it in production ({@link NetworkCallGuard}, VYB-0940);</li>
 *   <li><b>checks the token budget</b> and refuses the call, with a reason in words, once the day's or the month's cap is
 *       reached ({@link AiBudgetService});</li>
 *   <li>makes the call (redaction, then the provider);</li>
 *   <li><b>records it</b> in the ledger: purpose, prompt version, model, the tokens the provider reported, duration and
 *       outcome ({@link AiUsageLedger}).</li>
 * </ol>
 * Nothing is recorded or counted when AI is not configured: a switched-off feature is not a failed call. No prompt, reply or
 * user is recorded.
 */
@Component
@Primary
public class MeteredModelGateway implements ModelGateway {

    private final ModelGateway delegate;
    private final AiBudgetService budget;
    private final AiUsageLedger ledger;
    private final MeterRegistry meters;
    private final NetworkCallGuard guard;

    public MeteredModelGateway(@Qualifier("redactingModelGateway") ModelGateway delegate, AiBudgetService budget,
                               AiUsageLedger ledger, MeterRegistry meters, NetworkCallGuard guard) {
        this.delegate = delegate;
        this.budget = budget;
        this.ledger = ledger;
        this.meters = meters;
        this.guard = guard;
    }

    @Override
    public boolean configured() {
        return delegate.configured();
    }

    @Override
    public String chatModel() {
        return delegate.chatModel();
    }

    @Override
    public String embeddingModel() {
        return delegate.embeddingModel();
    }

    @Override
    public ChatReply chat(ChatRequest request) {
        if (!delegate.configured()) return delegate.chat(request); // refuses, naming why; not a call, so nothing to record
        guard.beforeNetworkCall("AI call: " + request.purpose());
        String version = request.promptVersion();
        refuseIfOverBudget(request.purpose(), version, "CHAT", delegate.chatModel(), request.kind());
        long start = System.nanoTime();
        try {
            ChatReply reply = delegate.chat(request);
            done(new AiUsageLedger.Entry(request.purpose(), version, "CHAT", reply.model() == null ? delegate.chatModel() : reply.model(),
                request.kind(), AiUsageLedger.Outcome.OK, reply.promptTokens(), reply.completionTokens(), since(start)));
            return reply;
        } catch (RuntimeException e) {
            done(new AiUsageLedger.Entry(request.purpose(), version, "CHAT", delegate.chatModel(), request.kind(),
                AiUsageLedger.Outcome.FAILED, null, null, since(start)));
            throw e;
        }
    }

    @Override
    public EmbeddingReply embed(String text, CallKind kind) {
        if (!delegate.configured()) return delegate.embed(text, kind);
        guard.beforeNetworkCall("AI call: embedding");
        refuseIfOverBudget("embedding", "n/a", "EMBEDDINGS", delegate.embeddingModel(), kind);
        long start = System.nanoTime();
        try {
            EmbeddingReply reply = delegate.embed(text, kind);
            done(new AiUsageLedger.Entry("embedding", "n/a", "EMBEDDINGS", reply.model() == null ? delegate.embeddingModel() : reply.model(),
                kind, AiUsageLedger.Outcome.OK, reply.promptTokens(), null, since(start)));
            return reply;
        } catch (RuntimeException e) {
            done(new AiUsageLedger.Entry("embedding", "n/a", "EMBEDDINGS", delegate.embeddingModel(), kind,
                AiUsageLedger.Outcome.FAILED, null, null, since(start)));
            throw e;
        }
    }

    private void refuseIfOverBudget(String purpose, String version, String endpoint, String model, CallKind kind) {
        try {
            budget.requireWithinBudget();
        } catch (AiProviderUnavailableException e) {
            meters.counter("ai.budget.refusals", "purpose", purpose).increment();
            ledger.record(new AiUsageLedger.Entry(purpose, version, endpoint, model, kind, AiUsageLedger.Outcome.BUDGET_REFUSED, null, null, 0));
            throw e;
        }
    }

    private void done(AiUsageLedger.Entry entry) {
        ledger.record(entry);
        Integer total = entry.totalTokens();
        if (total != null) budget.recordUsed(total);
        meters.counter("ai.calls", "purpose", entry.purpose(), "outcome", entry.outcome().name()).increment();
        if (total != null) meters.counter("ai.tokens", "purpose", entry.purpose()).increment(total);
    }

    private static long since(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
