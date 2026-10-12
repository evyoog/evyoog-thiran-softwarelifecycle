# Trace UI: linking by picker, two more matrices, impact lists

Added by VYB-0943 (Phase 6, Sprint 9, F19). No migration: it reads and writes what already exists. Code: `RequirementLinkedWorkService`, `TraceMatrixService` (`com.vyoog.trace`), `DefectLifecycleService#linkRequirement`, `ChangeRequestService#computeImpactDetail`, and under `frontend/src/features/`: `trace/` (`LinkDialog`, `LinkedWorkPanel`, `ReleaseMatrixPage`, `DefectMatrixPage`, `MatrixScope`, `linkWork.ts`, `matrices.ts`) and `analytics/ImpactLists.tsx`, `impactItems.ts`. Decision: D36.

## 1. Linking a requirement by picking, not pasting

The requirement detail's Trace Links section used to take a direction, a link type and a pasted id, for another requirement only. It now has **Linked work** (what the requirement is linked to, by name, and a **Link…** button) and a dialog with a tab per kind. **Each kind keeps the mechanism it already had**; nothing here adds a link type or a table:

| Kind | Picker | What it does | Who may (the server decides; its refusal is shown as written) |
|---|---|---|---|
| Requirement | Search by name, a direction and a link type | A `trace_link` between two requirements | The create-or-edit role |
| Test case | Search by key or title | `TEST VERIFIES REQUIREMENT` trace link | The create-or-edit role |
| Design node | The nodes of the application's design flow | The design-node mapping (`PUT /design/nodes/{id}/requirements/{id}`) | The create-or-edit role |
| Release | Releases whose scope can still change (planned or open; a frozen one says "reopen it") and a **required reason** | Commits the requirement to the release scope (one release at a time; a scope movement and an audit event) | The baseline role |
| Defect | Defects that have **no requirement yet** | Sets the defect's requirement (`PUT /defects/{id}/requirement`) | The QA (verify) role |

**A defect has one requirement, and linking never moves it.** A defect that already has a requirement is not offered, and the endpoint refuses it (409, naming the requirement it is on). A requirement that a newer version has replaced (D35) is refused too. The link is audited as `defect.requirement-linked`. `GET /defects?untraced=true` lists the ones that can still be linked.

`GET /api/v1/requirements/{id}/linked` returns the names behind the panel: tests, design nodes, the release, defects, and the application the requirement sits in (none for an unplaced one, in which case the design tab says to place it first). Read by any signed-in person.

## 2. Two more matrices, beside requirement by test

One page per matrix with a strip to move between them (Requirements, then Tests, Releases or Defects). Each is for one **application**: its requirements are those in one of its capabilities or on the application itself, not deleted.

- **Requirement by release** (`GET /api/v1/trace/release-matrix/{applicationId}`). Rows are all the requirements; columns are the releases not yet released plus any released one holding one of this application's requirements; a mark says "in". A requirement is in one release at a time. **Gaps only** shows the approved, not-replaced requirements in no release. A line says how many are in a release and how many are waiting.
- **Requirement by defect** (`GET /api/v1/trace/defect-matrix/{applicationId}?includeClosed=false`). One column per defect in view (open by default), marked in its requirement's row, with its severity as a glyph and a word; row counts. Requirements with no defect are hidden unless asked for. **A defect has one requirement, so each column has exactly one mark** and the page says so. The open defects with **no requirement** are listed apart under the matrix, so nothing is dropped.

Marks are a glyph and a word, never colour alone, and none is amber. A replaced requirement says so in its row.

## 3. The items behind a change request's impact counts

`POST /api/v1/change-requests/{id}/impact` still returns the four counts and now also `items`: the requirements (each marked as **named by the change request** or **reached downstream**), the tests, the applications and the briefs. They come from the same walk of the trace graph as the counts (`computeImpactDetail`), so a list is as long as its count. In Analytics, Change requests, each count is a toggle that opens its list; each requirement links to its page. The counts stored on the change request are unchanged.

## Not here

- The detail page still lists requirement-to-requirement links by id (`from REQUIREMENT 1a2b3c4d…`); naming them is not part of this row.
- No unlink for tests, design, releases or defects from the dialog (a design node, a release scope item and a trace link each have their own unlink where they already did; a defect's requirement is not cleared from here).
- Moving a defect from one requirement to another is still an edit of the defect, not a link.
- The matrices have no export and no paging; an application with thousands of requirements returns them all.
- An "Impact" section on a requirement's own page (what changing it would affect) was offered and not chosen.

## Tests

`TraceUiIT` (`VYB0943_AC1` to `AC11`, real PostgreSQL and HTTP), `linkWork.test.ts` (`AC12` to `AC15`), `matrices.test.ts` (`AC16` to `AC19`), `impactItems.test.ts` (`AC20`, `AC21`).
