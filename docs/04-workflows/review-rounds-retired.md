# Review rounds: retired

Decided by D26 (accepted 2026-10-12) and done by VYB-0944 (Phase 6, Sprint 9, F17). Migration: `V053__retire_review_rounds.sql`.

A review round was a separate record: someone opened it for a set of requirements, named reviewers and approvers, and they signed it. It had a backend (since Phase 2) and no screen since session 41. The requirement lifecycle (D15 to D17: six statuses, a named role on every move, separation of duties on the move itself) is what runs, so the rounds are retired rather than given a screen.

## What is gone

- The `/api/v1/reviews` endpoints (open, list, get, comments, comment, sign, close) and the code behind them (`com.vyoog.review`). They answer 404.
- The two My Work task kinds that read rounds, `REVIEWER_PENDING` and `APPROVER_AWAITING`. Five kinds remain: author wording, developer implement and re-implement, tester verify and re-verify (still derived, never authored, D19's dismissal unchanged).
- The `sod` gap rule ("Separation of duties violation"), which watched approver signatures on rounds. It is marked retired (`gap_rule_template.retired_at`) and no longer listed on the rules screen. Its findings still open were resolved by the migration, and the retirement is on the audit log (`rule.retired`). Dismissed and accepted findings stay as decided. The grant-level rule `rbac-sod` is separate and stays.
- The review calls in the frontend client, and the "Review closes" entries on the My Work calendar (the calendar still shows clarification due dates, which it labels so).

## What is kept

The tables `review`, `review_item`, `review_participant` and `review_comment`, with every row, are **read-only history**. Nothing is dropped and nothing writes to them. Old `task_completion` rows of the two retired kinds stay too; they match nothing and break nothing.

## Lifecycle stages

A requirement's REVIEW and APPROVAL stages (`LifecycleHistoryService`, and the six-stage spine counts) used to come only from round signatures, so a requirement that never had a round showed neither. They now come from:

| Stage | Reached when | Who and when |
|---|---|---|
| REVIEW | A recorded move into REVIEWED, or an old round's reviewer signature | The earlier of the two |
| APPROVAL | A recorded move into APPROVED, or an old round's approver signature | The earlier of the two |

The moves are the `requirement.transitioned` events on the audit log, the same source as the History section (D34). A requirement that went through an old round keeps its stages; one counted both ways is counted once.

## Who may do what instead

Moving a requirement to REVIEWED or APPROVED is the transition with its per-edge role and separation-of-duties rule (D17, `RequirementTransitionAuthorizer`). `AccessRule.REVIEW` (Reviewer, Approver, Compliance Lead, Architect) remains and is used for findings and for reviewing a trace link.
