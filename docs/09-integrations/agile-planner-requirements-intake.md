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
201 Created (this call made it), 200 OK (a repeat), or 202 Accepted (the Function had changed: held as a proposal)
{ "requirement_id": "VY-1042", "status": "draft", "created_at": "...", "traceability": { "source_function_id": "FN-123" },
  "proposal": { "proposal_id": "...", "state": "pending" } }   // proposal only on a 202
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
| The same Function again with **changed content**: **202**, and the change is held as a **proposal** on the requirement (below). The requirement is not touched; the response's `status` is its own current one. | D24: a later change is a proposal for a person to accept, never an overwrite (VYB-0919, D51). |
| The same Function sent at the same moment, even under different keys, becomes **one** requirement. | A unique index decides; the loser answers as a repeat. |
| After creation the requirement is ours: a person may edit it under the normal revision and approval rules. | D24 answer 3. |

Limits (a 400 with the reason): `Idempotency-Key` required (at most 200 characters), `source_system` must be `agile_planner`, `source_function_id` and `title` required (title at most 300, an id at most 200), `description` at most 20,000, at most 50 criteria of at most 2,000 characters each.

## Later changes: proposals (VYB-0919, D51)

A changed Function is stored as a proposal (`planner_change_proposal`, V061) with the words the Planner sent. People see it on the requirement ("The Agile Planner changed Function FN-123": now against proposed, hatched and marked as the Planner's) and are told (notification kind "Planner change proposed", urgent, to those who may edit the requirement where it sits, any administrator, and its owner).

| Rule | Why |
|---|---|
| At most one proposal per Function is waiting. A newer change replaces the one nobody has decided; the Planner sending the requirement's **own current words** again closes it. The same changed words again (any key) are the same proposal. | The Planner may send many times; a person should face one question, the latest. |
| **Accept** (`POST /api/v1/requirements/{id}/planner-proposals/{proposalId}/accept`) is an edit of the requirement by the person who accepts, under the normal rules: the title, statement and priority become the proposal's, as a new revision made by that person; type and placement are kept; the criteria the requirement lacks (same words exactly) are added at the end and **none is removed**. | D24 answer 3 (the requirement is ours, edited under normal rules). A criterion may already be linked to tests, so nothing the Planner stops sending is deleted. |
| An **approved** requirement cannot take an accept (409, saying so): it changes only through a change request. The proposal keeps waiting. | The normal rule for approved content (VYB-0390); connecting a proposal to a change request is not built. |
| **Reject** (`.../reject`, optional note up to 500 characters) changes nothing and records who decided and when. The same words sent again are not asked again; different words are a new proposal. | |
| Deciding needs the role that edits requirements, where the requirement sits (administrators always); never a service account. Reading is for any signed-in person. | The same rule as editing (AccessPolicyTest). |
| Two people deciding at once: one wins, the other gets 409. A decided proposal is kept. | |

## What is not built

- **Telling the Planner back** is built (VYB-0918): the four signed webhooks are in `agile-planner-webhooks.md`. It has not been tried against the real Planner.
- **Placement from the Planner's capability**: needs the Macro Planner mapping (blocked, D24).
- **Showing on the screen that a requirement came from the Planner,** other than a waiting proposal. The link exists in the database; nothing else reads it yet.
- **A proposal for an approved requirement cannot be turned into a change request** from the screen; a person raises one by hand.
- **No derived task** in My Work for a waiting proposal; a notification (bell, and email if the person chose it) is the only prompt.
- The Planner's Feature has no counterpart here; its id is kept in the link only.
- OAuth2 token validation is the application's normal Keycloak resource-server check; whether the Planner's real client-credentials flow and `Idempotency-Key` handling work against it is unconfirmed (the Planner's open questions 2 and 3).
