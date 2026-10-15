# Agile Planner: telling it back (VYB-0918, first part)

What this application sends the Agile Planner about the requirements the Planner sent it (`agile-planner-requirements-intake.md`), as decided in D24 and D52. **Not exercised against the real Planner**: its ALM integration is not built and there is no test instance, so this is proven only by this repository's tests, which play the Planner over real HTTP. **The Planner has not defined the event bodies; the ones below are this application's proposal, built from what its documents do say, and nobody there has confirmed them.**

Row VYB-0918 is split. **Built (0918a):** the signed-webhook path, the queue, retries and ordering, and `requirement.status_changed`. **Not built (0918b):** `design_artifact.created`, `test_case.created` and `test_result.recorded`; the queue already accepts them (V062 lists all four types).

## Setting it up (a person with Administration access; nothing is sent until this is done)

1. Administration, Connected systems, the connection **`agile-planner`** (it exists, unconfigured, since V062). Set its configuration to `{"baseUrl": "https://<the Planner's host>"}`. The webhook path `/webhooks/alm`, the signature header and the signature scheme are filled in by this application and need not be written.
2. Set the **shared secret** (the same value the Planner verifies signatures with). Without it, or without the URL, the connection says what is missing and events wait.
3. Connector health (Administration) then shows the connection and its recent sends; a send that fails three operations in a row marks it degraded.

## What is sent

`POST {baseUrl}/webhooks/alm`, one request per event, `Content-Type: application/json`:

| Header | Value |
|---|---|
| `X-ALM-Signature` | `hmac-sha256=` and the hex HMAC-SHA256 of the exact body bytes, keyed by the shared secret (the Planner's own scheme) |
| `Idempotency-Key` | `planner-event:<event_id>`, the same on every retry |

`requirement.status_changed`, sent whenever the status of a requirement **the Planner sent** changes (by a person, or in a bulk edit). Any other requirement sends nothing.

```
{ "event": "requirement.status_changed", "event_id": "<uuid>", "occurred_at": "2026-10-15T09:00:00Z",
  "version": 41, "requirement_id": "VY-1042", "source_function_id": "FN-123",
  "status": "approved", "previous_status": "reviewed" }
```

`requirement_id` is the key people see and the Planner got back when it sent the Function. Statuses are lower case: `draft`, `in_review`, `reviewed`, `needs_revision`, `approved`, `rejected`. `version` is one number that only grows, so the Planner can ignore an older event than one it has already applied (its own rule: an older version never overwrites a newer one). A reason given for a move is **not** sent.

## Delivery

| Rule | Why |
|---|---|
| The event is written in the same transaction as the change; a change that is refused or rolled back sends nothing. | The Planner is never told something that did not happen. |
| A job sends queued events every 30 seconds (at most 25 a pass). | A Planner that is down never slows or fails a change. |
| A failed send is tried again after 1, 2, 4 and 8 minutes, then left **FAILED** with its reason. (Each send also retries inside itself, as every connector does.) | |
| A later event about a requirement waits for an earlier one of the same requirement that is still waiting. | The Planner sees one requirement's changes in the order they happened. |
| While the connection is not set up, events wait; they go out once it is. One still waiting after 14 days is marked FAILED. Sent and failed events are removed after 30 days. | Setting it up late loses nothing recent; the queue cannot grow forever. |
| A sent event is never sent twice (the sync log and the key). A crash between sending and recording can still send one twice: the Planner should treat `event_id` as the identity. | |

## What is not built

- The other three events (0918b), and their bodies are not even proposed yet.
- **No screen lists the events that failed for good.** They are in the table `planner_outbound_event` (state FAILED, with the reason) and the connector's sync log is on Connector health; nothing lets a person re-send one.
- Nothing is sent for a requirement created here that the Planner did not send, and nothing for a change other than its status (its wording, priority or placement).
- The Planner's `X-TENANT-ID` and OAuth2 token are for calls *to* this application (see the intake guide); the webhook to the Planner is authenticated by its signature only, as its documents say. If its webhook also wants an API key, the connection's `auth` can list `API_KEY` too.
