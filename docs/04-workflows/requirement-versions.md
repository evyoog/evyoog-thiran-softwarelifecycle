# New versions of an approved requirement

Added by VYB-0942 (Phase 6, Sprint 9, F18). Migration `database/migrations/V052__requirement_fork.sql`. Code: `RequirementForkService` (`com.vyoog.requirements`), `RequirementService#transition`, `POST /api/v1/requirements/{id}/fork`, `GET /api/v1/requirements/{id}/versions`, `GET /api/v1/change-requests/{id}/forks`, `frontend/src/features/requirements/VersionsPanel.tsx`. Decision: D35 (it settles the question D17 deferred).

## The model

**A new version is a new requirement.** It has its own id and its own key (VY-nnnn), starts as a DRAFT, and carries two links: `forked_from_id` (the approved requirement it was copied from) and `forked_for_change_request_id` (the approved change request that allowed it). The database requires both or neither.

1. A person with the edit role on the approved requirement forks it **for an approved change request that names it** (the same check as editing in place: the request is APPROVED and the requirement is in its scope).
2. The fork is an ordinary requirement. It goes through the six-state machine and its own review. Its author is the person who forked it (only they may submit it, D17).
3. **When the fork is approved**, the original is marked superseded by it (`superseded_by_id`, audit event `requirement.superseded`). **The original's status stays APPROVED**: no seventh status, D17 stands.
4. Until then the original is untouched and is still the approved one.

`requirement.version` (D17, always 1) is not used: a version is a separate requirement, not a counter on one.

### What the fork copies

| Copied | Not copied |
|---|---|
| Title, statement, type, priority, rationale, placement (product, application or capability) | Tests, design, code and defect links: they covered the old wording, and the new version has to be covered afresh (the same principle as a revision bump demoting a verified requirement). They stay on the original. |
| Acceptance criteria, in order | `CONFLICTS` links: a conflict has to be judged again against the new wording. |
| **Upstream** links: another requirement that `SATISFIES`, `DERIVES` or `REFINES` the original (unless that parent is itself replaced), so the new version is not an orphan | Revision history: the fork starts at revision 1; the original's history stays on the original. |

## Rules enforced

- Only an **APPROVED** requirement can be forked, and only if it is **not already superseded**.
- **One new version in progress at a time** per original: a second fork is refused (409) while one exists that is not deleted and not rejected; a rejected or deleted one frees the original. A unique index (`requirement_one_open_fork_idx`) holds this against two requests at once. A rejected fork cannot be reopened while another is open.
- A fork **cannot be approved** if something else has already replaced its original.
- A **superseded** requirement takes **no new trace links** (either end) and **cannot be committed to a release**; the error names its replacement.
- The edit-in-place path under an approved change request (`PATCH /requirements/{id}?changeRequestId=`) is **unchanged**. A change request can be acted on either way.

## Seeing it

- The requirement detail shows a line above the page: for a fork, "A new version of VY-12, made for CR-3" with a link; for a replaced original, "Replaced by VY-47. It still reads Approved, and takes no new links and no release"; for an original with a fork open, "A new version is in progress: VY-47". Each has a glyph and words, with the other requirement's status badge, and none is amber.
- **Fork a new version** appears for an APPROVED, not replaced requirement with no new version in progress. It asks for the approved change request (only those that name this requirement are listed) and opens the new draft.
- `GET /change-requests/{id}/forks` lists what was forked for a change request, with each original.
- Audit: `requirement.forked` (on the new requirement: from, change request, criteria and links copied), `requirement.fork-opened` (on the original), `requirement.superseded` (on the original, when the fork is approved).

## Not here

- **Applying the change request is still manual** (`POST /change-requests/{id}/apply`). Approving a fork does not mark the request applied, and apply does not check that each requirement in scope was dealt with.
- Nothing moves the original's downstream links (tests, design, code) to the new version. They stay on the original and the new version is covered afresh.
- A superseded requirement still appears in the grid as APPROVED; only its detail page says it is replaced.
- Rationale, acceptance criteria and links are not part of a revision (VYB-0941), so a fork's history starts empty of them.
- A requirement already in a release's scope when it is superseded stays there (only new commits are refused).

## Tests

`RequirementForkIT` (`VYB0942_AC1` to `AC11`, real PostgreSQL and HTTP), `versions.test.ts` (`AC12` to `AC14`).
