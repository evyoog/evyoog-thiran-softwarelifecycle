# AI model gateway

Added by VYB-0936 (Phase 6, Sprint 8, F28). Code: `com.vyoog.ai` in `vyoog-domain` (`ModelGateway`, `OpenAiGateway`, `GatewayCircuitBreaker`, `ChatRequest`, `ChatReply`, `EmbeddingReply`, `CallKind`, `JsonModelClient`). Decision: D29.

Before this row, five classes each built and sent their own request to OpenAI (`OpenAiEmbeddingProvider`, `OpenAiLlmAdjudicator`, `OpenAiRequirementRewriteAdvisor`, `OpenAiTraceRelationClassifier`, and the shared `OpenAiChatClient` used by six more): the same block, with its own timeout, its own error reading, no retry and no protection against a provider that is down.

## The rule

**Every model call goes through `ModelGateway`.** Nothing else in `com.vyoog.ai` may use `java.net.http`; `ArchitectureTest.onlyTheGatewayTalksHttpToAModelProvider` fails the build if one does. A provider is an implementation of the interface; `OpenAiGateway` is the first and only one (`vyoog.ai.provider=openai`, the default).

## What it does

| | |
|---|---|
| Not configured | `configured()` is `vyoog.ai.enabled` and a non-blank key. Otherwise every call refuses with a reason in words and sends nothing. |
| Retries | A connection failure, a timeout, or a status of `408 425 429 500 502 503 504` is retried with the connector framework's `RetryPolicy` (exponential backoff with jitter; a longer `Retry-After` is honoured up to the cap). Any other answer (400, 401 and so on) is definite and is not repeated. |
| Interactive calls (`CallKind.INTERACTIVE`) | A person is waiting (rewrite suggestion, trace proposal, an embedding on a write). Two attempts, 12 s each, inside a total limit of 25 s (`AI_INTERACTIVE_DEADLINE_SECONDS`). |
| Batch calls (`CallKind.BATCH`) | Nobody is watching (a sweep's adjudication, document analysis, brief elaboration, test-case suggestion). Up to four attempts, the caller's own per-attempt timeout (20 s for adjudication, `AI_ANALYSIS_TIMEOUT_SECONDS`, default 90, for analysis), inside 300 s (`AI_BATCH_DEADLINE_SECONDS`). |
| Total limit | A retry is started only if its wait plus at least one second of attempt still fits inside the limit. |
| Circuit breaker | One per endpoint kind (chat, embeddings), in memory, per application instance. It opens after 5 failed calls in a row (`AI_BREAKER_FAILURE_THRESHOLD`) and refuses calls without sending anything. After 60 s (`AI_BREAKER_OPEN_SECONDS`) one trial call is let through: success closes it, failure opens it again. A "failed call" is one whose attempts were all spent on retryable failures. A definite answer (a 400, a 401) or a reply that cannot be read shows the provider is up, and is not counted. |
| Reply | `ChatReply` carries the text, the model and finish reason, and the token counts the provider **reported** (null when it did not say; never estimated). Recording them is VYB-0939. |
| Failure | Every failure is an `AiProviderUnavailableException` with a reason. "Unknown" is never faked as a zero vector or an empty answer; callers still refuse rather than guess (CLAUDE.md rule 6). |

## Redaction in front of it

Classes are handed `RedactingModelGateway` (`@Primary`), which cleans the text and calls this gateway (VYB-0937, [`../security/ai-redaction.md`](../security/ai-redaction.md)). The provider gateway above is the bean `openAiGateway` and is injected nowhere else.

## What stayed with each caller

The prompt, how the reply is read, and the refusal to invent an answer: the rewrite advisor, the trace classifier (drops a key it was not offered and a link type that does not exist), the adjudicator, the embedding provider (refuses a vector that is not 1536 wide), and the six JSON agents through `JsonModelClient` (asks for a JSON reply, refuses one cut off at the token limit).

## Configuration

`vyoog.ai.*` in `application.yml`; the existing keys and environment variables are unchanged (`AI_ENABLED`, `AI_API_KEY`, `AI_API_URL`, `AI_MODEL`, `AI_EMBEDDINGS_URL`, `AI_EMBEDDINGS_MODEL`, `AI_ANALYSIS_TIMEOUT_SECONDS`). New: `AI_PROVIDER`, `AI_INTERACTIVE_DEADLINE_SECONDS`, `AI_BATCH_DEADLINE_SECONDS`, `AI_BREAKER_FAILURE_THRESHOLD`, `AI_BREAKER_OPEN_SECONDS`. No secret has a default.

## Not here (later rows)

The review endpoint for AI proposals is done (VYB-0938, [`../../04-workflows/ai-proposal-review.md`](../../04-workflows/ai-proposal-review.md)). Later rows: persisting model, prompt version and token counts, budgets and the usage screen (VYB-0939), moving model calls out of database transactions (VYB-0940). The gateway does not show its breaker state anywhere yet.

## Tests

`OpenAiGatewayTest` (`VYB0936_AC1` to `AC10`, a real HTTP server on localhost, the clock and the waits the test's), `GatewayCircuitBreakerTest` (`AC8`), `AiCallersOnGatewayTest` (`AC11` to `AC15`), `ModelGatewayWiringIT` (`AC16`, the real application context), `ArchitectureTest`.
