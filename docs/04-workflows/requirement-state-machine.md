<!-- Split verbatim from the former backend/docs/vyoog-build-specification.md (lines 1210–1277). Section numbers are unchanged; docs/02-requirements/SPECIFICATION-INDEX.md maps every section to its file. -->

> Note: the state machine was later replaced (D15 to D17): the current one is DRAFT / IN_REVIEW / REVIEWED / NEEDS_REVISION / APPROVED / REJECTED, with no VERIFIED status. See `docs/DECISIONS.md`.

## 8. Requirement state machine

The lifecycle is a gated state machine, not a free-text status field. Illegal
transitions must be rejected by the service layer with a reason.

**D17 (2026-08-24): a full replacement, not an extension of D15/D16's five-state
machine.** Six states — `DRAFT`, `IN_REVIEW`, `REVIEWED`, `NEEDS_REVISION`, `APPROVED`,
`REJECTED` — and exactly eight legal edges, enforced as a single table
(`RequirementStatus.allowedNext()`), never scattered if/else checks. `NEEDS_REVISION` is
new: a decision maker can send a `REVIEWED` requirement back to its author instead of
approving or rejecting it, and a `REJECTED` requirement now reopens into
`NEEDS_REVISION` rather than `DRAFT` — a rejection already carries the reason the author
needs to act on. Rejecting directly from `IN_REVIEW` is gone: a decision is only made
once a review is actually complete. `APPROVED` is fully terminal — no transition leaves
it (D15's `APPROVED → REJECTED` and `APPROVED → IN_REVIEW` are both gone with it);
editing an approved requirement is refused exactly as before, through the existing
change-request path, which edits content directly and was never gated by this machine.
Forking a new version record when someone edits an `APPROVED` requirement is
deliberately deferred — `requirement.version` exists (always 1 today) reserved for it,
but the mechanism itself needs its own decision about how a fork relates to trace links,
design-diagram nodes and key uniqueness, and was not decided as a side effect of this one.

**Permission per edge, in one place.** "Author" is an identity check (`owner_id` or
`created_by`), not a grant — submitting, withdrawing and resubmitting are author-only.
"Reviewer" and "decision maker" reuse the existing `REVIEWER`/`APPROVER` roles (§4.4) at
the requirement's own scope — no new role. A decision maker may not decide on a
requirement they own or authored (SoD, the same principle already enforced when signing
a review round, VYB-0304, now also enforced on the transition itself) — this closes a
gap D15 explicitly left open, where the generic transition endpoint enforced no
role/SoD at all, for any edge. `RequirementTransitionAuthorizer` is the one place this
lives, shared by the single-transition endpoint and bulk edit, so the two cannot drift
the way the reason/blocking-clarification guards briefly did before VYB-0810.

```
                  ┌────────────────────────────────────────────────────┐
                  ▼                                                    │
  ┌───────┐  submit  ┌───────────┐  verify   ┌──────────┐  approve  ┌──────────┐
  │ DRAFT │─────────▶│ IN_REVIEW │──────────▶│ REVIEWED │──────────▶│ APPROVED │ (terminal)
  └───────┘           └───────────┘            └──────────┘            └──────────┘
      ▲                     │                       │
      │ withdraw            │                       │ send back / reject
      └─────────────────────┘                       ▼
                                          ┌──────────────────┐  reopen  ┌──────────┐
                                          │  NEEDS_REVISION   │◀────────│ REJECTED │
                                          └──────────────────┘          └──────────┘
                                                    │
                                                    │ resubmit
                                                    ▼
                                               IN_REVIEW
```

| Transition | Entry condition | Actor | Side effects |
|---|---|---|---|
| DRAFT → IN_REVIEW | Statement non-empty; ≥1 acceptance criterion **or** an explicit override with reason | Author | Creates review round; notifies participants |
| IN_REVIEW → DRAFT | — | Author (withdraw) | — |
| IN_REVIEW → REVIEWED | All required reviewers signed; no open blocking clarification | Reviewer on scope | The "Verify" action — Manual today (read the requirement on the Design screen, then confirm); AI-assisted is deferred (Rule 6), shown only as a disabled placeholder |
| REVIEWED → APPROVED | — | Decision maker (Approver) on scope, **not** the owner (SoD) | Step-up auth; signature recorded; revision frozen — this is the pipeline's terminal status |
| REVIEWED → REJECTED | Reason mandatory | Decision maker (Approver) on scope, **not** the owner (SoD) | Returns to author with the reason on the timeline |
| REVIEWED → NEEDS_REVISION | Reason mandatory | Decision maker (Approver) on scope, **not** the owner (SoD) | Returns to author with the reason on the timeline; `revision_count` increments |
| NEEDS_REVISION → IN_REVIEW | — | Author (resubmit, skips DRAFT) | — |
| REJECTED → NEEDS_REVISION | No fresh reason needed — the rejection already carries one | Decision maker (Approver) or Administrator | Reopens the requirement; `revision_count` increments |

**Bulk status change** must apply this machine per row and report skipped rows with the
reason, rather than moving everything and losing the evidence — the prototype's bulk edit
modal already states this behaviour.

---
