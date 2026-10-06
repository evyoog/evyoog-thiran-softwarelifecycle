# Release state machine and readiness gates

Added by VYB-0928 (Phase 6, Sprint 6, F13). Migration `database/migrations/V045__release_lifecycle.sql`. Code: `com.vyoog.release` (`ReleaseState`, `ReleaseLifecycleService`, `ReleaseGateConfigService`, `ReleaseGate`), endpoints in `ReleaseLifecycleController`.

Before this row a release's state existed (`release.state`, V001) but nothing ever changed it.

## States and moves

```
PLANNED ──▶ OPEN ──▶ FROZEN ──▶ RELEASED          (final)
                ▲        │
                └────────┘   reopen (needs a reason)
```

- Only those moves exist. Anything else (PLANNED to FROZEN, RELEASED to anything, a move to the same state) is refused with 409.
- **The scope is locked while FROZEN and RELEASED.** Committing a requirement to the release or removing one from it is refused (409; for a frozen release the message says to reopen it first). In PLANNED and OPEN the scope changes as before.
- Freezing does **not** create a baseline: baselines remain their own explicit action.
- Moving a release never writes `requirement.status` (CLAUDE.md rule 3).

## Readiness gates

Two moves are guarded: **OPEN to FROZEN** and **FROZEN to RELEASED**. Each has the same five gates, switched on or off per move, platform-wide:

| Gate | Passes when |
|---|---|
| `SCOPE_NOT_EMPTY` | at least one requirement is committed |
| `ALL_APPROVED` | every committed requirement is Approved (D16: the terminal status) |
| `NO_CRITICAL_GAPS` | no open critical finding on a committed requirement |
| `NO_BLOCKED_ITEMS` | nothing committed is unverified, conflicting or unowned (the existing blocked-items rule) |
| `VERIFIED_SHARE` | at least a threshold percentage of the committed requirements are verified (`is_verified`); the only gate with a threshold, 0 to 100 |

Defaults: freezing needs `SCOPE_NOT_EMPTY`, `ALL_APPROVED`, `NO_CRITICAL_GAPS`; releasing adds `NO_BLOCKED_ITEMS`; `VERIFIED_SHARE` exists on both, off, at 100%. An administrator edits them (`PUT /api/v1/release-gates/{transition}/{gate}`, body `{"enabled", "threshold"}`); every edit is audited (`release-gate.updated`, before and after). Anyone signed in can read them (`GET /api/v1/release-gates`).

## Moving a release

`POST /api/v1/releases/{id}/transition` with `{"to": "FROZEN", "reason": "...", "override": false}`.

- **Who:** the Approver (matrix "Baseline", the rule every release write uses); a platform administrator passes too. Signing a release off with step-up is VYB-0929 and is not here.
- **A failing gate refuses the move** with 409 and a problem document that lists each failing gate (`failedGates`: gate and a plain-language detail) and says `overridable: true`.
- **Override:** the same call with `"override": true` and a reason proceeds. The reason and the gates that were failing are recorded with the move. An override with no reason is refused (400); an override when nothing fails is not recorded as one.
- **Reopen** (FROZEN to OPEN) needs a reason, never gates.
- Concurrent moves of one release are serialised by a row lock; refused moves leave no trace in the history.

`GET /api/v1/releases/{id}/gates` returns the moves available now, each with its enabled gates evaluated (what stands in the way); `GET /api/v1/releases/{id}/history` returns every recorded move: from, to, reason, whether it overrode, the gates it overrode, who and when.

## Data

- `release_transition`: one row per move (`from_state`, `to_state`, `reason`, `overridden`, `failed_gates` JSON, `changed_by`, `changed_at`). The database refuses an override recorded without a reason and the gates.
- `release_gate`: the ten settings (two moves times five gates), seeded and only ever updated. The database refuses a threshold on any gate but `VERIFIED_SHARE`.

## Audit

`release.transitioned` (before and after state, reason, whether overridden and which gates), `release-gate.updated`.

## Tests

`ReleaseLifecycleIT` (`VYB0928_AC1` to `AC10`); `AccessPolicyTest` (the transition endpoint is Baseline); `ForeignKeyIndexIT`.
