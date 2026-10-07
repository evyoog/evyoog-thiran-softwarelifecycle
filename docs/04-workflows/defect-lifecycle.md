# Defect lifecycle

Added by VYB-0931 (Phase 6, Sprint 6, F15). Migration `database/migrations/V047__defect_lifecycle.sql`. Code: `com.vyoog.defect` (`Defect`, `DefectService`, `DefectLifecycleService`), endpoints in `DefectController`; screen: Quality, Defects (`DefectDetailPanel`).

Before this row a defect could be raised, classified and closed; the FIXED state existed in the enum (and `Defect.markFixed`) but no service or endpoint used it, a closed defect could not be reopened, and a defect could not be edited, commented on or linked.

## States and moves

```
OPEN ──fix──▶ FIXED ──close (needs a root cause)──▶ CLOSED
  │             │                                       │
  │             └────── reopen (needs a reason) ──▶ OPEN ◀── reopen (needs a reason)
  └──────── close (needs a root cause) ──────────────▶ CLOSED
```

- **Mark fixed** is OPEN to FIXED only. **Close** needs a root cause (as before) and is allowed from OPEN or FIXED. **Reopen** is FIXED or CLOSED back to OPEN and needs a written reason (a blank reason is refused with 400, a defect that is already open with 409).
- Every move writes a row to `defect_transition` (from, to, reason, who, when) and an audit event: `defect.fixed`, `defect.closed`, `defect.reopened`. The root cause is kept on a reopen.
- A closed defect is not edited: reopen it first.
- Defect moves never touch `requirement.status` (CLAUDE.md rule 3).

## Who may do what

| Action | Endpoint | Who |
|---|---|---|
| Mark fixed | `POST /defects/{id}/fix` | the defect's assigned developer, or a Tester (Verify), or an administrator; checked in the handler against the defect |
| Close, reopen, edit, assign, link | `POST /defects/{id}/close`, `POST .../reopen`, `PUT /defects/{id}`, `PUT .../assignment`, `PUT .../links` | Tester (`VERIFY`), anywhere |
| Comment | `POST /defects/{id}/comments` | any signed-in person (a service account is refused) |
| Read the list, the detail, the comments | `GET /defects`, `GET /defects/{id}`, `GET /defects/{id}/comments` | any signed-in person |

The role matrix has no Developer column for defects, so "assigned developer" is decided against the defect, not by a role. No new role or matrix column was added.

## Assignment and notices

Assigning sets the developer and the tester (either may be cleared); both must be existing users. Only a person newly assigned is told (`defect-routed-developer`, `defect-routed-tester`). Marking fixed tells the tester (`defect-fixed`); reopening tells the developer (`defect-reopened`). Notices coalesce within the digest window like every notification.

## Links

A defect carries three optional links, each set explicitly: a test case, a test run and a release (`defect.test_case_id`, `test_run_id`, `release_id`; deleting the target clears the link, it never deletes the defect). The failed step a defect was raised from (VYB-0926) is separate and unchanged; the detail shows both.

## Comments

`defect_comment`: append-only, enforced by a trigger (no update, no delete), 1 to 4000 characters after trimming. Audit event `defect.commented`.

## The list

`GET /defects?state=OPEN|FIXED|CLOSED|ALL` (default OPEN) with `severity`, `releaseId`, `assignedTo` (developer or tester) and `q` (key or title). An unknown state is a 400. Worst severity first, then newest.
