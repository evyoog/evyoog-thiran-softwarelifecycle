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

## Standard templates (VYB-0950, D27, D41)

A **template** is a starter set of clauses for one framework, shipped with the application (a JSON file under `backend/vyoog-domain/src/main/resources/clause-templates/`), so that a register does not start empty. The first is **ISO 26262** (road vehicles, functional safety), chosen for D27: parts 2, 3, 4, 6 and 8 at the level of the standard's clauses, 24 in all, with the software and supporting processes that this product's trace from requirement to test serves. Hardware (part 5) is left out.

**What a template is not.** Its wording is **our own short paraphrase of what each clause is for, never the standard's text** (the standards are copyrighted). The part-and-clause numbers (`6-9` for part 6, clause 9) come from public descriptions of the 2018 edition and have **not** been checked against the licensed text: the template says so, and so does the screen. Check each against your copy before relying on it, and edit or delete what does not fit.

**Applying one** (Compliance, Clauses tab, **From a template**; `POST /api/v1/clauses/templates/{id}/apply`; Administrators, like the rest of the register) adds the clauses the register does not already have, matching on standard and section (case-insensitive). **A clause that is already there is left exactly as it is**, so applying twice adds nothing and an edit survives. One audit event records what was added (`clause.template_applied`). The clauses are not rescanned one by one: the unmapped-clause rule picks them up at its next sweep. Afterwards they are ordinary clauses: map requirements to them, edit them, delete them. `GET /api/v1/clauses/templates` (any signed-in person) lists the templates with how many of each the register already has.

**A template file is checked when the application starts**: an id, a name, a standard and at least one clause, every clause with a section, a title and a text, no section used twice, and every length within the register's own limits; a malformed file stops the application rather than failing on first use. To add a framework, add a file; nothing else changes.
