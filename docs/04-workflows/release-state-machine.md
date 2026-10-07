# Release state machine and readiness gates

Added by VYB-0928, VYB-0929 and VYB-0930 (Phase 6, Sprint 6, F13). Migrations `database/migrations/V045__release_lifecycle.sql` and `V046__release_signoff.sql`. Code: `com.vyoog.release` (`ReleaseState`, `ReleaseLifecycleService`, `ReleaseGateConfigService`, `ReleaseGate`), endpoints in `ReleaseLifecycleController`.

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

- **Who:** the Approver (matrix "Baseline", the rule every release write uses); a platform administrator passes too.
- **A failing gate refuses the move** with 409 and a problem document that lists each failing gate (`failedGates`: gate and a plain-language detail) and says `overridable: true`.
- **Override:** the same call with `"override": true` and a reason proceeds. The reason and the gates that were failing are recorded with the move. An override with no reason is refused (400); an override when nothing fails is not recorded as one.
- **Reopen** (FROZEN to OPEN) needs a reason, never gates.
- Concurrent moves of one release are serialised by a row lock; refused moves leave no trace in the history.

`GET /api/v1/releases/{id}/gates` returns the moves available now, each with its enabled gates evaluated (what stands in the way); `GET /api/v1/releases/{id}/history` returns every recorded move: from, to, reason, whether it overrode, the gates it overrode, who and when.

## Sign-off: freezing and releasing are signature events (VYB-0929)

Moving **into FROZEN** and **into RELEASED** is a signature event (spec 4.5), so it needs **step-up authentication at the moment of the move**, checked now rather than at sign-in. Opening, and reopening a frozen release, do not.

- Without the configured step-up level (`vyoog.stepup.required-level`, default `step-up`; the token's `acr` claim) the call is refused with **401** and a problem document naming the level required (`requiredLevel`). The SPA is expected to re-authenticate at that level and retry. Nothing moves and nothing is recorded.
- A service-account token cannot sign (a person is required), and step-up never replaces the Approver role: a Tester with step-up is still 403.
- One signer: the Approver who makes the move signs it. The same person may freeze and release. The signature is recorded on the move itself: **who** (`changed_by`), **the authentication level achieved** (`signature_acr`), **when the person last authenticated** (`auth_time`, from the token's `auth_time` claim; may be unknown) and when (`changed_at`). The database refuses a freeze or release recorded with no level (for new rows; the rows V045 wrote before this have none).
- The domain refuses a signed move with no signature too, so no caller can skip it. The audit event `release.transitioned` carries the level and authentication time.
- `GET /releases/{id}/gates` marks each move with `signatureRequired`.
- Still to do: a recorded sign-off by more than one person, and a separate-person rule (the person who releases differs from the one who froze); both were offered and not chosen.

## The release being prepared (Home)

`GET /api/v1/releases/current` (any signed-in person) returns the release being prepared: among those **OPEN or FROZEN**, the one with the **earliest target date** (a release with no date sorts last, then by name). 204 when none is. It carries the release's state and target date, its available moves each with their readiness gates evaluated (what stands in the way, in words) and the **blocked requirements** (unverified, conflicting, unowned). The Home screen's "Blocking the release" panel reads it: the state and date, whether the next forward move is ready and each failing check, that the move is a sign-off needing step-up, and the blocked requirements. Before VYB-0928 nothing could be OPEN, so that panel was always empty.

## Release notes export and the scope picker (VYB-0930)

**Export.** `GET /api/v1/releases/{id}/notes/export?format=markdown|docx` (any signed-in person) returns the release notes as a file download (`Content-Disposition: attachment`, `X-Content-Type-Options: nosniff`, a file name made safe from the release's name). The content is the notes the Notes tab shows: the release name, state and target date (or "no target date"), when it was generated and how many are approved and held; the **Approved** requirements grouped by capability; and, in their own section, the requirements committed but **held** short of Approved, which are never left out. An unknown format is 400 and an unknown release 404.

- **Markdown:** every piece of text from a requirement is escaped so it cannot become formatting, a link or HTML, and line breaks are flattened so one requirement stays one list item.
- **Word (.docx):** written by `ReleaseNotesExporter` with **no document library**: a .docx is a zip of a few XML parts, and release notes need a title, two heading levels and list lines. Text is XML-escaped and characters XML 1.0 cannot carry are dropped, so a stray control character in a title cannot corrupt the file. The same notes give the same bytes. It has been read back with an independent reader (python-docx); it has **not** been opened in Word or LibreOffice (see the session log).
- Nothing in the notes is a cost, an estimate or a per-person figure.

**Scope picker.** `GET /releases/{id}/scope/items` returns what is committed with key, title, status and capability. `GET /releases/{id}/candidates?q=&page=&size=` returns the requirements a person can pick, searchable by key or title (wildcards are literal; deleted requirements are not offered), each naming the release that already holds it, if any, so the picker shows it as taken. `POST /releases/{id}/scope/bulk` commits several with **one reason, all or nothing** (Approver, anywhere): the whole call is refused, with every offending requirement named, if the scope is locked, the reason is missing, a requirement does not exist, or one is committed to another release; one already committed here is skipped and reported; at most 200 at a time. Each requirement still gets its own scope movement and `release.scope_added` audit event. On the Releases screen the Scope tab shows key and title instead of ids, the picker (search, tick several across searches, one reason), and, for a frozen or released release, says in words that the scope is locked and offers no way to change it.

## Data

- `release_transition`: one row per move (`from_state`, `to_state`, `reason`, `overridden`, `failed_gates` JSON, `changed_by`, `changed_at`, and for a freeze or release `signature_acr` and `auth_time`). The database refuses an override recorded without a reason and the gates.
- `release_gate`: the ten settings (two moves times five gates), seeded and only ever updated. The database refuses a threshold on any gate but `VERIFIED_SHARE`.

## Audit

`release.transitioned` (before and after state, reason, whether overridden and which gates, and for a signed move the level and authentication time), `release-gate.updated`.

## Tests

`ReleaseLifecycleIT` (`VYB0928_AC1` to `AC10`), `ReleaseSignOffIT` (`VYB0929_AC1` to `AC7`), `homeRelease.test.ts` (`VYB0929_AC8`), `ReleaseNotesExporterTest` and `ReleaseNotesScopeIT` and `releaseScope.test.ts` (`VYB0930_AC1` to `AC9`); `AccessPolicyTest` (the transition endpoint is Baseline); `ForeignKeyIndexIT`.
