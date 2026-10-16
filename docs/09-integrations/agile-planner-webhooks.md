# Agile Planner: telling it back (VYB-0918)

What this application sends the Agile Planner about the requirements the Planner sent it (`agile-planner-requirements-intake.md`), as decided in D24 and D52. **Not exercised against the real Planner**: its ALM integration is not built and there is no test instance, so this is proven only by this repository's tests, which play the Planner over real HTTP. **The Planner has not defined the event bodies; the ones below are this application's proposal, built from what its documents do say, and nobody there has confirmed them.**

Row VYB-0918 was built in two parts. **0918a:** the signed-webhook path, the queue, retries and ordering, and `requirement.status_changed`. **0918b:** `design_artifact.created`, `test_case.created` and `test_result.recorded` (this page describes all four).

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

`requirement.status_changed`: sent whenever the status of a requirement **the Planner sent** changes (by a person, or in a bulk edit). Any other requirement sends nothing.

```
{ "event": "requirement.status_changed", "event_id": "<uuid>", "occurred_at": "2026-10-15T09:00:00Z",
  "version": 41, "requirement_id": "VY-1042", "source_function_id": "FN-123",
  "status": "approved", "previous_status": "reviewed" }
```

All four share these fields first: `event`, `event_id`, `occurred_at`, `version`, `requirement_id`, `source_function_id`. Each then adds its own. Only requirements **the Planner sent** are reported; every other requirement queues nothing.

`design_artifact.created`: a design artifact (a node on the application's design) now covers the requirement, whether the design was generated or a person linked the node by hand. Reported once per node and requirement; unlinking and linking again is not a new artifact. The two pipeline milestones, "Testing" and "Deployment", are derived from the requirements and are not design artifacts, so they are not reported.

```
{ ..., "event": "design_artifact.created", "design_artifact_id": "<node uuid>", "kind": "step", "name": "VY-1042 Pause a subscription" }
```

`test_case.created`: a test case now verifies the requirement, whether a person drafted it against the requirement or a CI result named it. Reported once per test case and requirement.

```
{ ..., "event": "test_case.created", "test_case_id": "TC-77", "title": "Pausing stops billing" }
```

`test_result.recorded`: a result against the requirement. From CI, when it is ingested; from a manual run, when the run is **completed** (not while it is filled in), for every case that has a result, **a blocked one included** (it writes no verification here, but the Planner's documents name it). Reported once per run, test case and requirement. A test case's `test_case.created` always comes before its first result.

```
{ ..., "event": "test_result.recorded", "test_case_id": "TC-77", "outcome": "pass", "build_reference": "build-2026.10.15-3", "requirement_revision": 4 }
```

`outcome` is `pass`, `fail` or `blocked`. `build_reference` is the CI build label, or the manual run's build label or, if it has none, its suite name. `requirement_revision` is the revision of the requirement the test was run against, so the Planner can tell a result for an older wording from one for the current.

`requirement_id` is the key people see and the Planner got back when it sent the Function. Statuses are lower case: `draft`, `in_review`, `reviewed`, `needs_revision`, `approved`, `rejected`. `version` is one number that only grows, so the Planner can ignore an older event than one it has already applied (its own rule: an older version never overwrites a newer one). A reason given for a move is **not** sent.

## How the Planner should verify a webhook (and what it can rely on)

The signature is the Planner's own scheme (`X-ALM-Signature: hmac-sha256=<hex>`). To verify: take the **exact bytes of the request body as received** (do not parse and re-serialise), compute HMAC-SHA256 with the shared secret, write it as lower-case hex, check the header starts with `hmac-sha256=` and compare the rest **in constant time**. Reject anything else. This is tested here with an independent implementation of exactly these steps, including a body with non-ASCII text (the bytes are UTF-8) and a body or secret that differs by one byte.

Replay: **there is no timestamp header**, and the body's `occurred_at` is covered by the signature but must not be used as a short freshness window: an event is retried for up to about 15 minutes after a failure, and one written while the connection was not set up waits up to 14 days. The identity to rely on is **`event_id`** (the same on every retry: keep the ones seen and ignore a repeat) together with **`version`** (only grows: ignore an event older than one already applied for the same requirement), which is the Planner's own documented rule.

## Delivery

| Rule | Why |
|---|---|
| The event is written in the same transaction as the change; a change that is refused or rolled back sends nothing. | The Planner is never told something that did not happen. |
| A job sends queued events every 30 seconds (at most 25 a pass). A pass stops after three sends fail in a row: the Planner is evidently down, and the rest wait for the next pass. | A Planner that is down never slows or fails a change, and is not hammered. |
| A failed send is tried again after 1, 2, 4 and 8 minutes, then left **FAILED** with its reason. (Each send also retries inside itself, as every connector does.) | |
| A later event about a requirement waits for an earlier one of the same requirement that is still waiting. | The Planner sees one requirement's changes in the order they happened. |
| While the connection is not set up, events wait; they go out once it is. One still waiting after 14 days is marked FAILED. Sent and failed events are removed after 30 days. | Setting it up late loses nothing recent; the queue cannot grow forever. |
| A sent event is never sent twice (the sync log and the key). A crash between sending and recording can still send one twice: the Planner should treat `event_id` as the identity. | |

## What is not built

- **A result is reported when it is recorded**, and a test case or design artifact when it first covers the requirement: an event is never taken back when a link is later removed or a result is later superseded (the Planner derives its own `test_status` from the results it has).
- **A requirement the Planner sends after its designs and tests already exist** is told nothing about them: only what happens from then on is reported.
- **Defects, risks and releases** are not reported.
- **No screen lists the events that failed for good.** They are in the table `planner_outbound_event` (state FAILED, with the reason) and the connector's sync log is on Connector health; nothing lets a person re-send one.
- Nothing is sent for a requirement created here that the Planner did not send, and nothing for a change other than its status (its wording, priority or placement).
- The Planner's `X-TENANT-ID` and OAuth2 token are for calls *to* this application (see the intake guide); the webhook to the Planner is authenticated by its signature only, as its documents say. If its webhook also wants an API key, the connection's `auth` can list `API_KEY` too.
