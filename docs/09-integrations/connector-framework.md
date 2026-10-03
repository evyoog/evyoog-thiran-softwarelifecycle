# Connector framework (VYB-0913)

How Vyoog sends to an external system, once, the same way for every system. It is the generic part only: nothing here knows the Agile Planner or any other particular tool. Its API contract and a test instance are still open (D24), and the connectors that need them are VYB-0914 onward.

## The pieces

| Class (`com.vyoog.integration.connector`) | What it is |
|---|---|
| `Connector` | The declaration: which registry connection it sends through, what it does, which operations it has. A Spring bean; `ConnectorRegistry` lists them. |
| `ConnectorOperation` | One request a connector wants made: connection, operation name, **idempotency key**, method, path, content type, body bytes. Everything that reaches a header or URL is validated (no header injection, no path that leaves the host). |
| `ConnectorExecutor` | Sends it: configuration, idempotency claim, authentication, retries with backoff, sync log, health. |
| `ConnectorConfig` / `ConnectorAuth` | What a connection's registry row must contain, and the authentication schemes. |
| `RetryPolicy` | What is retried and how long to wait. |
| `ConnectorSyncLog` | `connector_sync_log` (V039): one row per operation. |
| `ConnectorHealthService` | NOT_CONNECTED, HEALTHY or DEGRADED per connection. |

The registry is the existing `integration_connection` table. The framework adds no connection table; it reads a row's `config` and `webhook_secret` and updates its failure counters.

## Configuring a connection

`integration_connection.config` (JSON) and `webhook_secret`:

```json
{
  "baseUrl": "https://planner.example.com/api",
  "auth": ["HMAC_SIGNATURE", "API_KEY"],
  "apiKey": "...",
  "apiKeyHeader": "X-API-Key"
}
```

| Rule | Why |
|---|---|
| `baseUrl` must be https; plain http is accepted only for `localhost`, `127.0.0.1`, `::1`. No credentials, query or fragment in it. | A key or a signature over cleartext is a leak. |
| `auth` is required. `["NONE"]` says there is none on purpose; an absent list is "not configured". `NONE` cannot be combined. | An unauthenticated connection must be a decision, not an omission. |
| `API_KEY` needs `apiKey` (header `apiKeyHeader`, default `X-API-Key`). `BEARER` needs `bearerToken`. `HMAC_SIGNATURE` needs the connection's `webhook_secret` and sends `X-Vyoog-Signature`, the hex HMAC-SHA256 of the exact body bytes (the scheme the existing planning push and inbound webhooks already use). | |

A connection that fails these is **not configured**: `execute` throws `ConnectorNotConfiguredException` naming the reason (never a secret), sends nothing, writes no log row and does not count as a failure. Secrets stay where the registry already keeps them; none is ever logged, put in an error message, or stored in the sync log.

## What `execute` does

1. Reads and validates the configuration.
2. **Claims the idempotency key** in the sync log. A key that already succeeded is not sent again (`ALREADY_DONE`); one being sent right now by another instance or thread is not sent twice (`IN_PROGRESS_ELSEWHERE`). A failed key can be tried again. A send left `IN_PROGRESS` by an instance that died is marked failed after 15 minutes so it cannot block the key forever. The guarantee is a partial unique index, so it holds across instances.
3. Sends with the key as an `Idempotency-Key` header, **the same on every retry**, so a receiver that supports it can recognise a repeat.
4. **Retries** connection failures, timeouts and `408 425 429 500 502 503 504` (`RetryPolicy`: up to 4 attempts, exponential backoff from 500 ms with jitter, capped at 30 s, a longer `Retry-After` honoured up to that cap, 10 s per request). Every other response, including every other 4xx and every redirect, is a definite answer and is not repeated. Redirects are never followed, so the credentials cannot be bounced to another host. A response is read up to 64 KB.
5. Writes the outcome to the **sync log** (attempts, last HTTP status, a short single-line reason with any of the connection's own secrets removed, the payload's size and SHA-256, never the payload).
6. Updates **health** and returns a `ConnectorResult`. A failed send is a result, not an exception.

All of its database writes are short transactions of their own, so the log is true even if the caller's transaction rolls back, and a claim is visible to other instances at once. A caller should still not hold a transaction open across a send.

## Health

| State | Meaning |
|---|---|
| `NOT_CONNECTED` | Not configured, or configured and nothing has succeeded yet. Never blank or zero (Principle 8). |
| `HEALTHY` | Connected and the configuration is usable. |
| `DEGRADED` | Three failed operations in a row (the registry's existing rule, `IntegrationConnection.DEGRADE_AFTER_FAILURES`). Cleared by the next success. |

Failures are counted per **operation**, not per attempt. When a connection becomes degraded or recovers, one audit event is recorded (`connector.degraded`, `connector.recovered`, system actor). `ConnectorHealthService.health(key)` also reports the last error, when, and the last success. The screen that shows this under Administration is VYB-0917; there is no HTTP endpoint for it yet.

## Settings

| Property (env) | Default | |
|---|---|---|
| `vyoog.connector.max-attempts` (`CONNECTOR_MAX_ATTEMPTS`) | 4 | |
| `vyoog.connector.initial-delay-ms` (`CONNECTOR_INITIAL_DELAY_MS`) | 500 | |
| `vyoog.connector.max-delay-ms` (`CONNECTOR_MAX_DELAY_MS`) | 30000 | |
| `vyoog.connector.request-timeout-ms` (`CONNECTOR_REQUEST_TIMEOUT_MS`) | 10000 | |
| `vyoog.retention.connector-sync-log-days` (`CONNECTOR_SYNC_LOG_RETENTION_DAYS`) | 90 | the nightly purge deletes older sync-log rows; at least 1 |

Metrics: `vyoog_connector_operations_total{connection,outcome}` (`succeeded`, `failed`, `already_done`, `in_progress_elsewhere`) and `vyoog_connector_attempts_total{connection}`.

## Not part of this row

The existing `BriefPushService` and `SignalsExportService` push straight to the `planning` connection and are **unchanged**: they do not retry, have no idempotency key and no sync log, and one of them makes its HTTP call inside a database transaction. Replacing them with a connector is VYB-0916. Field ownership (VYB-0914), the approval-triggered `function.upserted` event (VYB-0915), the health screen (VYB-0917) and inbound sync (S4) are the later rows.
