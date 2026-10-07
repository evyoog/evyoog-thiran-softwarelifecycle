package com.vyoog.ai;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * VYB-0937 (F27): the {@link ModelGateway} everything else is handed. It cleans what goes out, calls the provider's
 * gateway, and puts personal data back in what comes in:
 * <ol>
 *   <li>Secrets are removed and personal data is replaced by tokens ({@link Redactor}), in the user text of a chat call
 *       and in the text of an embedding. The system prompt is the caller's own fixed instruction and is not touched,
 *       so a caller must never put data in it.</li>
 *   <li>The provider is called with the cleaned text.</li>
 *   <li>In a chat reply, each token is replaced by the original value. A secret is never restored. An embedding is a
 *       vector, so there is nothing to restore.</li>
 * </ol>
 * <b>Fails closed.</b> If the text cannot be checked (the redactor or the list of people fails), nothing is sent and the
 * call is refused like any other provider failure. A switched-off class is the administrator's choice
 * ({@link RedactionSettings}); a failure is never a reason to send more.
 *
 * <p>Only counts and class names are logged and measured ({@code ai.redactions}), never a value.
 */
@Component
@Primary
public class RedactingModelGateway implements ModelGateway {

    private static final Logger log = LoggerFactory.getLogger(RedactingModelGateway.class);

    private final ModelGateway provider;
    private final Redactor redactor;
    private final RedactionSettings settings;
    private final KnownPeople people;
    private final MeterRegistry meters;

    public RedactingModelGateway(@Qualifier("openAiGateway") ModelGateway provider, Redactor redactor,
                                 RedactionSettings settings, KnownPeople people, MeterRegistry meters) {
        this.provider = provider;
        this.redactor = redactor;
        this.settings = settings;
        this.people = people;
        this.meters = meters;
    }

    @Override
    public boolean configured() {
        return provider.configured();
    }

    @Override
    public String chatModel() {
        return provider.chatModel();
    }

    @Override
    public String embeddingModel() {
        return provider.embeddingModel();
    }

    @Override
    public ChatReply chat(ChatRequest request) {
        if (!provider.configured()) return provider.chat(request); // refuses, naming why; nothing to clean first
        Redaction cleaned = clean(request.user(), "chat");
        ChatReply reply = provider.chat(cleaned.changedAnything() ? request.withUser(cleaned.text()) : request);
        if (cleaned.tokens().isEmpty()) return reply;
        String content = reply.content();
        boolean json = request.jsonObject() || (content != null && (content.stripLeading().startsWith("{") || content.stripLeading().startsWith("[")));
        return new ChatReply(cleaned.restore(content, json), reply.model(), reply.finishReason(), reply.promptTokens(), reply.completionTokens());
    }

    @Override
    public EmbeddingReply embed(String text, CallKind kind) {
        if (!provider.configured()) return provider.embed(text, kind);
        return provider.embed(clean(text, "embedding").text(), kind);
    }

    private Redaction clean(String text, String endpoint) {
        Redaction r;
        try {
            Set<DataClass> disabled = settings.disabled();
            r = redactor.redact(text, disabled, disabled.contains(DataClass.PERSON) ? null : people.names());
        } catch (RuntimeException e) {
            log.error("[ai] redaction failed; nothing was sent to the provider: {}", e.toString());
            throw new AiProviderUnavailableException(
                "Nothing was sent to the AI provider: the text could not be checked for secrets and personal data.", e);
        }
        if (r.changedAnything()) {
            for (Map.Entry<DataClass, Integer> e : r.counts().entrySet()) {
                meters.counter("ai.redactions", "class", e.getKey().name(), "endpoint", endpoint).increment(e.getValue());
            }
            log.info("[ai] redacted before sending ({}): {}", endpoint, r.counts());
        }
        return r;
    }
}
