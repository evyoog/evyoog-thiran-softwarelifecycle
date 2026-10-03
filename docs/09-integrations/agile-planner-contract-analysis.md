# Agile Planner contract: what its repository says (analysis for VYB-0914)

**Status: analysis, not a decision.** It records what the Agile Planner's own repository defines, where that differs from the Phase 6 plan for the connector rows, and what has to be decided before VYB-0914 (field ownership) can be built. D24 stays Open.

**Source read:** `evyoog/evyoog-thittam-agile`, branch `main`, commit `c5b497b` (a read-only clone; nothing was changed there). Paths below are inside that repository. Quotation marks are its words.

## 1. What exists in the Agile Planner today

| Thing | State |
|---|---|
| ALM integration (the integration with this application, "SW Life Cycle (ALM)") | **Not built.** Sprint 2026.4.1 `01-Requirements.md` §4: "Missing: No ALM integration." Planned for October 2026. |
| Macro Planner integration | Not built either. |
| `Feature` and `Function` entities | **Do not exist in its code.** The backend has `Ticket`, `TicketItems`, `TicketWorkSession`, `Board`, `BoardColumn`, `BoardField`, `Tag`. ADR-03 (2026.4.1 architecture §1.3): "Extend `Ticket`, do not fork it: Ticket becomes the Function/backlog item by adding nullable FKs". |
| Tests | "No test sources currently exist under `backend/src/test`" (`docs/08-architecture/backend-architecture/tms-backend.md` §1). |

So everything below is a **design in documents**, not a running API. Its own open questions say as much (section 6).

## 2. The contract it documents

Sources: `docs/07-database/data-model/agile-planner-data-model-api-contracts.md` §1 and §3; `docs/02-requirements/FRD/sprints/2026.4.1-Oct-2026/01-Requirements.md` (AP-C14, FR-2026.4.1-001 to 006); `docs/08-architecture/system-architecture/sprints/2026.4.1-Oct-2026/02-Architecture-Design.md` §2.1, §4.2, §5.

**Direction. The Planner pushes into this application.** "Push Features/Functions to the SW Life Cycle (ALM) application as the seed for Requirements, hold back-references only." The call, from the Planner to this application:

```
POST /v1/requirements        Authorization: Bearer <oauth token>   Idempotency-Key: {function_id}-{updated_at}   X-TENANT-ID
{ source_system:"agile_planner", source_function_id, source_feature_id, source_capability_id, source_goal_id,
  title, description, priority:"high", acceptance_criteria:[ ... ] }
-> { requirement_id:"ALM-REQ-30442", status:"draft", created_at, traceability:{ source_function_id } }
```

**Back to the Planner, from this application,** as signed webhooks to `POST /webhooks/alm` (header `X-ALM-Signature: hmac-sha256=...`, body with `event`, `event_id`, `occurred_at`): `requirement.status_changed`, `design_artifact.created`, `test_case.created`, `test_result.recorded` (outcome pass, fail or blocked, run time, build reference). The Planner derives `Function.test_status` (not_started, in_progress, passing, failing) from the results.

**Rules it states:** version-based conflict resolution (an older `version` never overwrites a newer one); "deprecate, never cascade-delete"; an unknown ALM requirement becomes a "shadow Requirement" in an unlinked report; the Planner holds back-references only (`alm_requirement_id`, `alm_design_id`, `alm_test_case_id`) and "ALM remains the system of record" for requirements, designs, test cases and results.

## 3. Where it differs from the Phase 6 register

| Register row | What it assumes | What the Planner repository says |
|---|---|---|
| VYB-0915 "Outbound `function.upserted`, triggered by approval" | this application sends a `function.upserted` event when something is approved | The name `function.upserted` appears nowhere in the Planner repository. Its outbound flow is the reverse: the Planner sends the Function. This application's outbound events are the four above. |
| VYB-0918 inbound `backlog_item.status_changed` | the Planner tells this application when a backlog item changes status | Not in the Planner documents. The only status flow described is ALM to Planner (`requirement.status_changed`). |
| VYB-0919 inbound `sprint.reassigned` | the Planner tells this application when a sprint changes | Not in the Planner documents. |
| VYB-0914 field-level ownership of Feature and Function | a field-by-field table exists or can be read from the Planner | **There is no field-level table.** Ownership is stated only per system (section 4). |
| VYB-0916 "keep the signed-payload format" | the signature format of the old planning push | The Planner's documented signature is `X-ALM-Signature: hmac-sha256=<hex>`, and the old push uses `X-Vyoog-Signature` with bare hex. They differ. |

The register's connector rows (0914 to 0921) were written against an event vocabulary that the Planner's documents do not contain, and with the main flow (Planner to this application, creating a Requirement) missing: there is no row for receiving `POST /v1/requirements`.

## 4. Ownership as stated, and as not stated

**Stated (per system, not per field):**

| System | Owns | Source |
|---|---|---|
| Agile Planner | execution: Functions, backlog, teams, sprints, tasks, progress; the derived `Function.test_status` | 2026.4.1 requirements §9; architecture §1.2 "Separate sources of truth" |
| Macro Planner | strategy: Goals, and the Application, Capability and Feature levels (read-only in the Planner) | same; data model §2.1 |
| This application (ALM) | the engineering artifacts: Requirement, Design Artifact, Test Case, Test Result, and a Requirement's status | architecture §3.2 DE-AP-22/23 |

**Fields the Planner design gives each entity** (data model §1.2): *Feature*: name, description, priority, business_value, status, board, capability, source_goal_id. *Function*: name, description, story_points, status, sprint, team, assignee, feature. What is sent across to this application is a subset: title (the Function's name), description, priority, acceptance criteria and the four source ids.

**Not stated anywhere, and so not something to be inferred** (each is a business rule):

1. After the first push, does a later change to the Function's title, description or acceptance criteria change the Requirement here, or is the Requirement's text owned here from then on? The Planner pushes "on creation or meaningful status change" (FR-001) and the idempotency key includes `updated_at`, which suggests later pushes, but `POST /v1/requirements` is the only call described and "meaningful" is not defined.
2. Can a person here edit a seeded Requirement's title or statement? (Here a requirement's text is revisioned and approved; the Planner may want to overwrite it.)
3. Priority: the Planner sends "high"; here it is CRITICAL, HIGH, MEDIUM or LOW.
4. Acceptance criteria: the Planner sends strings; here they are `acceptance_criterion` rows with their own lifecycle.
5. A Planner *Feature* has no counterpart here (a Requirement is placed under a Capability). The Phase 6 plan adds a Feature level only when importing from Macro Planner (S7).
6. Status vocabularies: a Planner Function's status is "a free-form String = the label of one of the board's `board_column` rows"; here the status is one of six fixed values.
7. One Function to one Requirement, or many to one: the Planner's own open question (section 6).

## 5. What the connector framework (VYB-0913) does not yet cover for this contract

| Contract needs | Framework today |
|---|---|
| Signature header `X-ALM-Signature: hmac-sha256=<hex>` | header name and format are fixed to `X-Vyoog-Signature` and bare hex |
| `X-TENANT-ID` on every call | no per-connection static headers |
| OAuth2 client-credentials token, refreshed | only a configured static bearer token |
| Receiving `POST /v1/requirements` with an `Idempotency-Key` | outbound only; no inbound endpoint exists (a Requirement-creation idempotency exists, VYB-0132, but it is not this call) |
| Receiving webhooks | the existing inbound webhook endpoint takes `X-Vyoog-Signature` and the delivery id; the Planner's body shape and header differ |

The framework itself (retries, backoff, idempotency, sync log, health) is usable for the four outbound events to the Planner.

## 6. The Planner's own open questions (`02-requirements/.../2026.4.1-Oct-2026/01-Requirements.md` §10; data model §4)

1. 1 Function = 1 Requirement, or N:1 ("confirm with the ALM team before build").
2. Confirm ALM honours `Idempotency-Key` ("many internal APIs don't by default").
3. Auth model: OAuth2 client-credentials is "assumed", to be confirmed.
4. Webhook ordering: if not guaranteed, the version-based discard is mandatory.
5. PMS versus Macro Planner: "confirm whether PMS is being replaced or kept alongside".

Items 1 to 3 are questions *to this application's team*, so the answers are owed from here.

## 7. Decisions needed before VYB-0914

A. **Which side's vocabulary and direction wins?** (a) Follow the Planner's documented contract: this application receives `POST /v1/requirements` from the Planner and sends the four webhooks back. Rows 0914 to 0921 are then rewritten around that. (b) Keep the register's `function.upserted` model and ask the Planner team to adopt it. (c) Both, in a stated order.
B. **The seven unstated rules in section 4**, at least 1 and 2, which decide who may edit which field.
C. **The Planner's open questions 1 to 3.**
