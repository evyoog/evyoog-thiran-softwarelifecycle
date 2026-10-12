# Requirement history and revision diff

Added by VYB-0941 (Phase 6, Sprint 9, F18). No migration: it reads what already exists. Code: `RequirementHistoryService` (`com.vyoog.requirements`), `GET /api/v1/requirements/{id}/history`, `frontend/src/features/requirements/HistorySection.tsx` and `revisionDiff.ts`. Decision: D34.

## What is on the timeline

One list per requirement, newest first, of two kinds of row:

| Kind | Where it comes from | What it carries |
|---|---|---|
| **REVISION** | `requirement_revision` (V001, VYB-0112): one immutable row for the creation and for every material edit. A save that changes nothing makes none (VYB-0113). | The whole snapshot: title, statement, type, priority, capability (id and name), the status at that time, who, when, the change reason. Any two can be compared from these. |
| **STATUS_CHANGE** | The `requirement.transitioned` audit event (VYB-0813). A move between statuses is not a revision: the words did not change. | From and to status, the reason given, who, when. |

Rows at the same instant list the status change above the revision it followed. A person who is no longer on record has a null name and the screen says so in words.

**Not on the timeline** (not snapshotted today): the rationale, acceptance criteria, links, attachments and comments. A change to those makes no revision and does not appear here. A comparison covers title, statement, type, priority and capability only.

### Audit retention

Revisions are never archived or deleted. Status changes live on the audit log, which is partitioned and whose old partitions are archived (VYB-0723), so a status change older than the retention window may be missing. The response says so: `statusHistoryMayBeIncomplete` is true when the requirement's own `requirement.created` event is no longer on the live log (also true for a requirement that predates the audit log). The screen then shows a note.

## The endpoint

`GET /api/v1/requirements/{id}/history` returns `{requirementId, currentRevision, currentStatus, statusHistoryMayBeIncomplete, entries[]}`. 404 for an unknown requirement. Read by any signed-in person, like `GET /requirements/{id}` itself; nothing is written, and no per-person figure is computed (CLAUDE.md rule 7: a name on a row is authorship, not a measure).

## The screen

A **History** section on the requirement detail page. The list shows the newest six rows and "Show all N". Each revision row says which fields it changed against the revision before it (the first says "Created"). Under the list, **Compare** has two pickers, From and To, defaulting to the revision before the latest and the latest.

- The **statement** is compared word by word, in the browser, from the two stored snapshots. An added word is **underlined and marked "added"**, a removed word is **struck through and marked "removed"** (for screen readers; a legend and a count of words added and removed say the same), and colour is only a third signal.
- Title, type, priority and capability are shown as old, then new.
- The comparison of a very large pair (more than four million token pairs) shows the old text as removed and the new as added instead of freezing the page.
- Whitespace changes are shown as changes and are not counted as words.
- The status badges use the same label, glyph and colour as everywhere else; no amber.

## Not here

- No restore or revert of an old revision, and no fork of an approved requirement (VYB-0942).
- No diff of acceptance criteria.
- No export or print of the history.
- No paging: the whole timeline is returned. A requirement with thousands of revisions is not expected.

## Tests

`RequirementHistoryIT` (`VYB0941_AC1` to `AC7`, real PostgreSQL, HTTP), `revisionDiff.test.ts` (`AC8` to `AC15`, 14 cases).
