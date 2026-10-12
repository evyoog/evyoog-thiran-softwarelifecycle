# Clause register and control mapping

Added by VYB-0947 (Phase 6, Sprint 10, F41). No migration: it reads and writes what already exists (`clause`, `trace_link`, `verification`). Code: `ClauseService` (`com.vyoog.clause`), `ClauseController`, and under `frontend/src/features/`: `Compliance.tsx` and `compliance/` (`ClausePanel`, `ClauseForm`, `complianceModel.ts`). Decision: D38. The screen is **Compliance** in the left menu.

A control clause is one requirement of a standard you are held to (an ISO 27001 control, a SOC 2 criterion). The register lists them, says which requirements satisfy each, and says whether that is backed by passing tests. **Evidence is read from the requirements and their tests; nothing is entered on a clause.**

## Mapping

A requirement is mapped to a clause by the **`SATISFIES` trace link** from the requirement to the clause: the link the unmapped-clause rule (`compl`) already reads, so the register and the rule agree. Nothing new is stored for the mapping.

A clause's **tests are the tests of its mapped requirements** (the `TEST VERIFIES REQUIREMENT` links), each with its latest result at the requirement's current revision: passed, failed, or not run at this revision. A test is not linked to a clause directly; that was offered and not chosen, to keep one place for evidence.

- Map and unmap from the clause panel: `PUT` and `DELETE /api/v1/clauses/{id}/requirements/{requirementId}`. Mapping again is not an error. Each is audited (`clause.requirement_mapped`, `clause.requirement_unmapped`).
- Who may: whoever may create trace links (`CREATE_EDIT_REQ`).
- Refused with a reason: a requirement that is **rejected**, one **replaced** by a newer version (D35; link the new one), one that does not exist.

## Coverage

Derived on every read, never stored. A requirement **counts** toward a clause unless it is deleted, rejected, or replaced by a newer version (it no longer satisfies anything).

| Coverage | When |
|---|---|
| Unmapped | no requirement counts |
| Mapped, not evidenced | at least one counts, and not every one is evidenced |
| Evidenced | at least one counts and every one that counts is **Approved with a passing test at its current revision** |

"Passing test at its current revision" is the existing `requirement_verification_state` predicate. Evidence from an earlier revision is shown as stale and does not count. This is a reading of test evidence; it never writes a requirement status (there is no VERIFIED status, D16). A requirement that stops counting stays listed on the clause, saying why.

Note the difference from the `compl` rule: that rule also treats a clause as mapped when a requirement is textually close enough. The register counts only the mapping a person made.

## The register

`GET /api/v1/clauses/register` (any signed-in person): every clause with how many requirements count and how many of those are evidenced; `GET /api/v1/clauses/{id}/mapping` for one clause with its requirements, evidence and tests. The screen filters by standard, coverage and words, and shows one summary line over the whole register. Coverage is a glyph and a word, never colour alone, and never amber.

**Administrators keep the register** (the rule from VYB-0906): `POST`, `PUT` and `DELETE /api/v1/clauses`. Each is audited (`clause.created`, `clause.updated` with the old text, `clause.deleted`), and a saved clause is rescanned. Rules: a standard and the text are required (at most 100 and 4000 characters; the section at most 100); **the same section of the same standard is not registered twice** (case-insensitive; clauses with no section may repeat); a clause that **anything is still linked to is not deleted** ("remove those links first"), and deleting one resolves its open findings. Administrators only was the choice; Compliance Lead holds no rights here.
