# Risk register

Added by VYB-0949 (Phase 6, Sprint 10, F41). Migration `V055__risk_register.sql` (`risk`, `risk_requirement`, `risk_release`). Code: `RiskService` and `RiskScale` (`com.vyoog.risk`), `RiskController`, and under `frontend/src/features/compliance/`: `RisksTab`, `RiskPanel`, `RiskForm`, `riskModel.ts`. The register is the **Risks** tab on the Compliance screen. Decision: D40.

A risk is something that could go wrong, how likely it is, how bad it would be, who owns it and what is being done about it, with the requirements it touches and the releases it threatens.

## Scoring

**Likelihood and impact, each 1 to 5** (likelihood: rare, unlikely, possible, likely, almost certain; impact: negligible, minor, moderate, major, severe). The **rating is their product (1 to 25)** and the **band** is read from it: Low 1 to 4, Medium 5 to 9, High 10 to 14, Critical 15 to 25. Rating and band are derived on every read, never stored, so the one definition (`RiskScale`, mirrored in `riskModel.ts`) governs every risk. Qualitative only: there is no cost, budget, effort or per-person field (rule 7).

## Status

`OPEN`, `MITIGATING`, `ACCEPTED`, `CLOSED`. Any status may move to any other. **Accepting or closing a risk needs a reason**, which is kept on the risk (and on the audit log) and shown with it: accepting a risk is a decision, and closing one says why it no longer matters. Closing records the date; reopening clears it. Moving to the status it already has is refused.

**A risk is never deleted.** It is closed, and stays, so the register is a record. **A closed risk is history**: it is not edited, and its links are not changed, until it is reopened.

## Links

- **Requirements it touches** (`PUT` and `DELETE /api/v1/risks/{id}/requirements/{requirementId}`). A deleted requirement is neither counted nor listed; a requirement that a newer version has replaced (D35) cannot be linked (link the new one).
- **Releases it threatens** (`PUT` and `DELETE /api/v1/risks/{id}/releases/{releaseId}`), in any release state.

A requirement's detail page lists, under Linked work, the **open** risks that name it, highest rating first. A closed risk is not listed there.

## The screen

A 5 by 5 grid of the risks that are not closed (a click on a cell lists them), a summary line, filters (status, band, words) and the list, highest rating first with closed ones last. Each band is a word and a glyph as well as a colour, and none is amber. The default view hides closed risks.

## Who may

**Approver, Compliance Lead, Architect or Administrator** add, edit, move and link (the access rule `RISK`, anywhere); everyone else signed in may read. Every change is audited: `risk.created`, `risk.updated` (with the old title, levels and owner), `risk.status_changed` (with the reason), `risk.requirement_linked` and `_unlinked`, `risk.release_linked` and `_unlinked`.

## Not part of this row

A risk does not **block** a release or change its readiness figures. The release readiness gates (VYB-0928) are separate, and making an open Critical risk a gate is a decision of its own.
