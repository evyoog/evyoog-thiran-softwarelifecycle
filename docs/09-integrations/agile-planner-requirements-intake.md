# Agile Planner: receiving a Function as a draft requirement (VYB-0915)

How this application accepts what the Agile Planner sends, as decided in D24 and D50. The Planner's own documents define the call (`docs/09-integrations/agile-planner-contract-analysis.md`, section 2); this page says what this application does with it. **Not exercised against the real Planner**: its ALM integration is not built yet and there is no test instance, so this is proven only by this repository's tests, which play the Planner.

## The call

```
POST /api/v1/planner/requirements
Authorization: Bearer <the Planner's client-credentials token>
Idempotency-Key: {function_id}-{updated_at}
Content-Type: application/json

{ "source_system": "agile_planner", "source_function_id": "FN-123", "source_feature_id": "FEAT-7",
  "source_capability_id": "CAP-3", "source_goal_id": "GOAL-1",
  "title": "...", "description": "...", "priority": "high",
  "acceptance_criteria": ["...", "..."] }
```

The path is this application's (`/api/v1/...`, not the contract's `/v1/requirements`, which would collide with the screens' own requirement endpoint); point the Planner's ALM base URL here. The names and the response are the contract's:

```
201 Created (this call made it)  or  200 OK (a repeat)
{ "requirement_id": "VY-1042", "status": "draft", "created_at": "...", "traceability": { "source_function_id": "FN-123" } }
```

`requirement_id` is the requirement's key as people see it. `X-TENANT-ID`, which the contract names, is not read: this application has one customer (D3).

## Who may call

A **registered service account holding the scope `planner:requirements`** (Administration, Service accounts), whose `client_id` is the Keycloak client the Planner uses; its token's `azp` claim is matched to it. A person's token, a service account without the scope, a client nobody registered, and no token are all refused (403, 401 for none) and nothing is created. Setting up the Keycloak client and giving it to the Planner is a human step.

## What it does

| Rule | Why |
|---|---|
| One Function is one requirement, linked by `source_function_id` (table `planner_function_link`, V060). | The first answer to the Planner's open question 1 (D24); the link is stored per requirement so it can be relaxed. |
| The requirement is a **draft** of type **FUNCTIONAL**, **placed nowhere**, with a null creator; the audit event says a **service** created it. | The Planner's capability id belongs to Macro Planner and cannot be mapped yet (the hierarchy sync is blocked). An unplaced requirement already shows the "not placed" gap; an administrator places it. |
| `title` is the title, `description` the statement (the title if there is none), each `acceptance_criteria` string becomes one criterion in order (blank ones are dropped). | The contract's fields, one to one. |
| `priority`: `critical`, `high`, `low` map to the same name, whatever the case; anything else, or nothing, is **MEDIUM**. | The Planner sends "high"; this application has four named levels. |
| The same `Idempotency-Key` answers with the requirement it first made (200). | The contract's own header. |
| The same Function again under another key, **same content**: 200, the requirement it already has, nothing changed. | The key includes a timestamp, so an unchanged Function can arrive under a new key. It never undoes an edit made here. |
| The same Function again with **changed content**: **409**, naming the requirement. Nothing is changed. | D24: a later change is a proposal for a person to accept, never an overwrite. Proposals are the next row; until then the change is refused, not lost: the Planner still holds it. |
| The same Function sent at the same moment, even under different keys, becomes **one** requirement. | A unique index decides; the loser answers as a repeat. |
| After creation the requirement is ours: a person may edit it under the normal revision and approval rules. | D24 answer 3. |

Limits (a 400 with the reason): `Idempotency-Key` required (at most 200 characters), `source_system` must be `agile_planner`, `source_function_id` and `title` required (title at most 300, an id at most 200), `description` at most 20,000, at most 50 criteria of at most 2,000 characters each.

## What is not built

- **Telling the Planner back** (`requirement.status_changed`, `design_artifact.created`, `test_case.created`, `test_result.recorded`, signed with `X-ALM-Signature`): the next rows.
- **Later changes as proposals** a person accepts or rejects: a later row. Until then a changed Function answers 409.
- **Placement from the Planner's capability**: needs the Macro Planner mapping (blocked, D24).
- **Showing on the screen that a requirement came from the Planner.** The link exists in the database; no screen reads it yet.
- The Planner's Feature has no counterpart here; its id is kept in the link only.
- OAuth2 token validation is the application's normal Keycloak resource-server check; whether the Planner's real client-credentials flow and `Idempotency-Key` handling work against it is unconfirmed (the Planner's open questions 2 and 3).
