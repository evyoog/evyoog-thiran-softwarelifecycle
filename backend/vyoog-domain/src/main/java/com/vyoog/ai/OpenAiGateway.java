package com.vyoog.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.integration.connector.RetryPolicy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * VYB-0936 (F28): the OpenAI implementation of {@link ModelGateway}, and the only class in the codebase that
 * sends a request to a model provider. It replaces the five copies of the same request block that
 * {@code OpenAiEmbeddingProvider}, {@code OpenAiLlmAdjudicator}, {@code OpenAiRequirementRewriteAdvisor},
 * {@code OpenAiTraceRelationClassifier} and {@code OpenAiChatClient} each carried.
 *
 * <p>What it adds to the copies' behaviour, and nothing else:
 * <ul>
 *   <li><b>Retries</b> a connection failure, a timeout, or a {@code 408 425 429 500 502 503 504}, with the connector
 *       framework's {@link RetryPolicy} (exponential backoff with jitter, a longer {@code Retry-After} honoured).
 *       Any other answer (a 400, a 401) is definite and is not repeated.</li>
 *   <li><b>A total time limit</b> per {@link CallKind}: a retry is only started if its attempt can still finish
 *       inside it.</li>
 *   <li><b>A circuit breaker per endpoint kind</b> (chat, embeddings), so an embeddings outage does not stop chat.</li>
 * </ul>
 * Same configuration keys ({@code vyoog.ai.*}, {@code AI_*}), same refusal to invent an answer.
 */
@Component
@ConditionalOnProperty(name = "vyoog.ai.provider", havingValue = "openai", matchIfMissing = true)
public class OpenAiGateway implements ModelGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenAiGateway.class);

    /** The least time worth starting another attempt for. */
    private static final Duration MIN_ATTEMPT = Duration.ofSeconds(1);

    private static final RetryPolicy INTERACTIVE_POLICY =
        new RetryPolicy(2, Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(12));
    private static final RetryPolicy BATCH_POLICY =
        new RetryPolicy(4, Duration.ofMillis(500), Duration.ofSeconds(30), Duration.ofSeconds(90));

    /** Test seam: waiting between attempts. */
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private enum Endpoint { CHAT, EMBEDDINGS }

    private final ObjectMapper json;
    private final HttpClient http;
    private final Clock clock;
    private final Sleeper sleeper;
    private final boolean enabled;
    private final String chatUrl;
    private final String embeddingsUrl;
    private final String apiKey;
    private final String chatModel;
    private final String embeddingModel;
    private final Duration interactiveDeadline;
    private final Duration batchDeadline;
    private final Map<Endpoint, GatewayCircuitBreaker> breakers;

    @Autowired
    public OpenAiGateway(
            ObjectMapper json,
            @Value("${vyoog.ai.enabled:false}") boolean enabled,
            @Value("${vyoog.ai.api-url:https://api.openai.com/v1/chat/completions}") String chatUrl,
            @Value("${vyoog.ai.embeddings-url:https://api.openai.com/v1/embeddings}") String embeddingsUrl,
            @Value("${vyoog.ai.api-key:}") String apiKey,
            @Value("${vyoog.ai.model:gpt-4o-mini}") String chatModel,
            @Value("${vyoog.ai.embeddings-model:text-embedding-3-small}") String embeddingModel,
            @Value("${vyoog.ai.interactive-deadline-seconds:25}") int interactiveDeadlineSeconds,
            @Value("${vyoog.ai.batch-deadline-seconds:300}") int batchDeadlineSeconds,
            @Value("${vyoog.ai.breaker-failure-threshold:5}") int breakerFailureThreshold,
            @Value("${vyoog.ai.breaker-open-seconds:60}") int breakerOpenSeconds) {
        this(json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), Clock.systemUTC(),
            d -> Thread.sleep(d.toMillis()), enabled, chatUrl, embeddingsUrl, apiKey, chatModel, embeddingModel,
            Duration.ofSeconds(interactiveDeadlineSeconds), Duration.ofSeconds(batchDeadlineSeconds),
            breakerFailureThreshold, Duration.ofSeconds(breakerOpenSeconds));
    }

    OpenAiGateway(ObjectMapper json, HttpClient http, Clock clock, Sleeper sleeper, boolean enabled, String chatUrl,
                  String embeddingsUrl, String apiKey, String chatModel, String embeddingModel,
                  Duration interactiveDeadline, Duration batchDeadline, int breakerFailureThreshold, Duration breakerOpenFor) {
        this.json = json;
        this.http = http;
        this.clock = clock;
        this.sleeper = sleeper;
        this.enabled = enabled;
        this.chatUrl = chatUrl;
        this.embeddingsUrl = embeddingsUrl;
        this.apiKey = apiKey;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.interactiveDeadline = interactiveDeadline;
        this.batchDeadline = batchDeadline;
        this.breakers = Map.of(
            Endpoint.CHAT, new GatewayCircuitBreaker("chat", breakerFailureThreshold, breakerOpenFor, clock),
            Endpoint.EMBEDDINGS, new GatewayCircuitBreaker("embeddings", breakerFailureThreshold, breakerOpenFor, clock));
    }

    @Override
    public boolean configured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String chatModel() {
        return chatModel;
    }

    @Override
    public String embeddingModel() {
        return embeddingModel;
    }

    /** The breaker for an endpoint kind, for tests and for a health read. */
    GatewayCircuitBreaker breaker(String name) {
        return breakers.get("chat".equals(name) ? Endpoint.CHAT : Endpoint.EMBEDDINGS);
    }

    @Override
    public ChatReply chat(ChatRequest request) {
        requireConfigured();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", chatModel);
        body.put("messages", List.of(
            Map.of("role", "system", "content", request.system()),
            Map.of("role", "user", "content", request.user())));
        if (request.jsonObject()) body.put("response_format", Map.of("type", "json_object"));
        body.put("max_tokens", request.maxTokens());
        body.put("temperature", request.temperature());

        return send(Endpoint.CHAT, chatUrl, request.kind(), request.timeout(), encode(body), response -> {
            JsonNode top = readTree(response.body());
            JsonNode content = top.at("/choices/0/message/content");
            if (content.isMissingNode() || content.isNull()) {
                throw new AiProviderUnavailableException("AI provider's response had no message content.");
            }
            return new ChatReply(content.asText(), chatModel, top.at("/choices/0/finish_reason").asText(""),
                intOrNull(top.at("/usage/prompt_tokens")), intOrNull(top.at("/usage/completion_tokens")));
        });
    }

    @Override
    public EmbeddingReply embed(String text, CallKind kind) {
        requireConfigured();
        String body = encode(Map.of("model", embeddingModel, "input", text == null ? "" : text));
        return send(Endpoint.EMBEDDINGS, embeddingsUrl, kind, Duration.ofSeconds(kind == CallKind.INTERACTIVE ? 12 : 20), body, response -> {
            JsonNode top = readTree(response.body());
            JsonNode node = top.at("/data/0/embedding");
            if (!node.isArray()) throw new AiProviderUnavailableException("AI provider's response had no embedding array.");
            float[] vector = new float[node.size()];
            for (int i = 0; i < vector.length; i++) vector[i] = (float) node.get(i).asDouble();
            return new EmbeddingReply(vector, embeddingModel, intOrNull(top.at("/usage/prompt_tokens")));
        });
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new AiProviderUnavailableException("AI is not configured (set AI_ENABLED=true and AI_API_KEY).");
        }
    }

    private <T> T send(Endpoint endpoint, String url, CallKind kind, Duration attemptTimeout, String body,
                       Function<HttpResponse<String>, T> parse) {
        GatewayCircuitBreaker breaker = breakers.get(endpoint);
        if (!breaker.tryAcquire()) {
            throw new AiProviderUnavailableException(
                "The AI provider is temporarily not being called after repeated failures (%s); trying again in about %d seconds."
                    .formatted(breaker.name(), breaker.secondsUntilTrial()));
        }

        RetryPolicy policy = kind == CallKind.INTERACTIVE ? INTERACTIVE_POLICY : BATCH_POLICY;
        Instant deadline = clock.instant().plus(kind == CallKind.INTERACTIVE ? interactiveDeadline : batchDeadline);
        String lastFailure = "no attempt was made";
        int attempts = 0;
        try {
            while (attempts < policy.maxAttempts()) {
                Duration left = Duration.between(clock.instant(), deadline);
                if (attempts > 0 && left.compareTo(MIN_ATTEMPT) < 0) break;
                Duration timeout = left.compareTo(attemptTimeout) < 0 ? left : attemptTimeout;
                attempts++;

                HttpResponse<String> response = null;
                Duration retryAfter = null;
                try {
                    response = http.send(HttpRequest.newBuilder().uri(URI.create(url)).timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                } catch (IOException e) {
                    lastFailure = "could not reach the AI provider: " + e.getMessage();
                    log.warn("[ai] {} attempt {} failed: {}", breaker.name(), attempts, e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    breaker.release();
                    throw new AiProviderUnavailableException("The AI call was interrupted.", e);
                }

                if (response != null) {
                    int status = response.statusCode();
                    if (status == 200) {
                        T result;
                        try {
                            result = parse.apply(response);
                        } catch (AiProviderUnavailableException e) {
                            breaker.recordSuccess(); // the provider answered; a reply that cannot be read is not an outage
                            throw e;
                        }
                        breaker.recordSuccess();
                        return result;
                    }
                    if (!policy.retryable(status)) {
                        breaker.recordSuccess(); // the provider answered; retrying will not change it
                        String detail = errorMessage(response.body());
                        log.warn("[ai] {} returned status={} detail={}", breaker.name(), status, detail);
                        throw new AiProviderUnavailableException("AI provider returned an error: " + detail);
                    }
                    lastFailure = "the AI provider returned status " + status + ": " + errorMessage(response.body());
                    retryAfter = retryAfter(response);
                    log.warn("[ai] {} attempt {} got retryable status={}", breaker.name(), attempts, status);
                }

                if (attempts >= policy.maxAttempts()) break;
                Duration wait = policy.delayBefore(attempts, retryAfter, () -> ThreadLocalRandom.current().nextDouble());
                if (Duration.between(clock.instant(), deadline).minus(wait).compareTo(MIN_ATTEMPT) < 0) break; // no room for another attempt
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    breaker.release();
                    throw new AiProviderUnavailableException("The AI call was interrupted.", e);
                }
            }
        } catch (AiProviderUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            breaker.release();
            throw e;
        }
        breaker.recordFailure();
        throw new AiProviderUnavailableException(
            "Could not get an answer from the AI provider after %d attempt%s: %s".formatted(attempts, attempts == 1 ? "" : "s", lastFailure));
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new AiProviderUnavailableException("Could not encode the AI request: " + e.getMessage());
        }
    }

    private JsonNode readTree(String body) {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new AiProviderUnavailableException("AI provider's response could not be parsed: " + e.getMessage());
        }
    }

    private String errorMessage(String body) {
        try {
            JsonNode message = json.readTree(body).at("/error/message");
            return message.isMissingNode() ? body : message.asText();
        } catch (Exception e) {
            return body;
        }
    }

    private static Duration retryAfter(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After").map(v -> {
            try {
                return Duration.ofSeconds(Long.parseLong(v.trim()));
            } catch (NumberFormatException e) {
                return null; // an HTTP-date form is not honoured; the backoff stands
            }
        }).orElse(null);
    }

    private static Integer intOrNull(JsonNode n) {
        return n.isNumber() ? n.asInt() : null;
    }
}
