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

Failures are counted per **operation**, not per attempt. When a connection becomes degraded or recovers, one audit event is recorded (`connector.degraded`, `connector.recovered`, system actor).

The outbound rules (a base URL, an auth scheme, a secret) apply only to a connection that sends. An **INBOUND-only** connection (a webhook source: CI, git, HR) is judged by whether it is connected and not degraded; "not configured" does not apply to it, and its "last success" is when its last verified delivery arrived. A BOTH connection reports whichever success is newer.

### The Administration screen (VYB-0917)

**Administration, Connector health** (platform administrators; the tab sits beside Connected systems, where a connection is set up). It shows, per connection:

- the state as a label, a glyph and a colour (never colour alone, never amber: amber means AI), degraded connections first;
- why it cannot send yet, in words ("Cannot send yet: no baseUrl is set"), or for a degraded one how many operations failed in a row and the receiver's last reason;
- when it last succeeded, and the latest operation sent;
- for a connection that sends, a "History" of its last 20 operations (when, operation, result and attempts, HTTP status, how long, the receiver's reason).

Principle 8: absence is said in words ("not connected", "never succeeded", "nothing sent yet", "receives only"), never blank or zero, and the reason a receiver gave is **hatched and marked "reported by <connection>"**, because Vyoog displays it and does not own it. It refreshes every 15 seconds.

Two read endpoints feed it, both platform administrators only (anyone else gets 403) and neither returns a configuration, a secret, a payload or a request header:

| Endpoint | Returns |
|---|---|
| `GET /api/v1/integrations/health` | every registered connection: key, state, why it cannot send, what it owns, direction, the bound connector and its operations, consecutive failures, last error and when, last success, latest sync |
| `GET /api/v1/integrations/{key}/sync-log?limit=20` | newest-first sync-log rows (limit 1 to 100); 404 for an unknown key |

## Settings

| Property (env) | Default | |
|---|---|---|
| `vyoog.connector.max-attempts` (`CONNECTOR_MAX_ATTEMPTS`) | 4 | |
| `vyoog.connector.initial-delay-ms` (`CONNECTOR_INITIAL_DELAY_MS`) | 500 | |
| `vyoog.connector.max-delay-ms` (`CONNECTOR_MAX_DELAY_MS`) | 30000 | |
| `vyoog.connector.request-timeout-ms` (`CONNECTOR_REQUEST_TIMEOUT_MS`) | 10000 | |
| `vyoog.retention.connector-sync-log-days` (`CONNECTOR_SYNC_LOG_RETENTION_DAYS`) | 90 | the nightly purge deletes older sync-log rows; at least 1 |

Metrics: `vyoog_connector_operations_total{connection,outcome}` (`succeeded`, `failed`, `already_done`, `in_progress_elsewhere`) and `vyoog_connector_attempts_total{connection}`.

## The planning connection (VYB-0916)

`PlanningConnector` (`com.vyoog.integration.planning`) is the first connector. It is the existing `planning` connection, and it replaces the two hand-written pushes: `BriefPushService` (a brief, as `multipart/form-data`, operation `brief.push`) and `SignalsExportService` (the scope signals, as JSON, operation `signals.push`). They still build their own payloads; the request itself (URL, signature, API key, retries, idempotency key, sync log, health) is the framework's. Neither push is transactional any more, so no database transaction is held open across the send.

**What stays the same, so a receiver and an existing configuration are not affected:**

- The wire format: the same body bytes, `X-Vyoog-Signature` carrying the bare hex HMAC-SHA256 of the exact body, `X-API-Key` when an API key is set, the same `Content-Type`.
- The configuration: the Administration screen still writes `{"pushUrl", "apiKey", "customerName"}` and the secret in `webhook_secret`. The connector reads that shape (`pushUrl` is the base URL; with no `auth` set, the schemes are the signature, plus the API key when there is one). A connection already written in the new shape (`baseUrl`, `auth`) is used as it is. Blank fields the screen saves are treated as not set. A trailing slash on `pushUrl` is sent as it was.
- The endpoints, their access rules, and the `{success, statusCode, error}` they return; the audit events `brief.pushed` and `signals.pushed`; the refusal texts "No push URL configured for "planning" — set one in Administration first." and "No shared secret configured for "planning" — the receiver couldn't verify this push anyway." (HTTP 409).
- Every click on "push" still sends: each push has its own idempotency key. The retries inside one push share it.

**What changes:**

| Before | Now |
|---|---|
| One attempt, 10 s | Up to 4 attempts with backoff for connection failures, timeouts and 408, 425, 429, 500, 502, 503, 504; a click can take up to about a minute when the receiver is down, and then reports the last failure |
| Plain http to any host accepted | https required; http only for localhost. An http `pushUrl` to a remote host is refused with a named reason |
| A `pushUrl` with a query string worked | Refused ("no query or fragment"): put the token in the API key or move it into the path |
| Receiver's error text returned in full | A short single-line excerpt, with the connection's own secrets removed |
| Redirects followed | Never followed (a 3xx is a failed push) |
| Two extra request headers | `Idempotency-Key` and `User-Agent: vyoog-connector` are added |
| No record of a push | A `connector_sync_log` row per push; the connection's health is the framework's |

## Not part of this row

Field ownership (VYB-0914), the approval-triggered event (VYB-0915) and inbound sync (S4) are later rows, and VYB-0914, 0915, 0918 and 0919 wait on the decisions in [`agile-planner-contract-analysis.md`](agile-planner-contract-analysis.md). Moving network calls out of database transactions elsewhere in the application is VYB-0940.
