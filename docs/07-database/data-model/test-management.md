# Test management: plans, suites, steps and runs

Added by VYB-0923 (entities, run creation), VYB-0924a (executing a run), VYB-0924b (evidence, retest) and VYB-0925 (verification records), the last three below (Phase 6, Sprint 5, F14). Migration `database/migrations/V040__test_plans_suites_runs_steps.sql`, schema `vyg_requirement`. Service: `com.vyoog.evidence.TestManagementService`; endpoints: `TestManagementController`.

VYB-0923 is the **entities and the creation of a run**; VYB-0924a is **executing** it (below). Evidence and retest are VYB-0924b, verification records bound to a requirement revision are VYB-0925 (both below), raising a defect from a failed step is VYB-0926 and the Quality screen is VYB-0927. Nothing here ever writes `requirement.status` (CLAUDE.md rule 3).

## Model

```
application ──< test_plan >── release (optional)
                  │
                  └──< test_suite ──< test_suite_case >── test_case ──< test_step
                          │
                          └──< test_run (kind MANUAL) ──< test_run_case ──< test_run_step     (the snapshot)
```

| Table | What it is | Rules |
|---|---|---|
| `test_plan` | A named plan for one application, optionally tied to a release. Key `TP-n` from `test_plan_key_seq`. | Name required (not blank). Belongs to an application (deleted with it); the release link is cleared if the release goes. No status. |
| `test_suite` | A named, ordered group inside a plan. | Name unique within the plan; `position` is 1-based, in creation order. |
| `test_suite_case` | Which existing test cases a suite holds, in order. | A case appears once per suite; removed if the case is deleted. A case may be in many suites. |
| `test_step` | One step of a test case: an **action** and its **expected result**. | Both required and not blank (the database checks it): VYB-0924 judges each step pass or fail against its expected result. Ordered, 1-based. Beside `test_case.description`, which is untouched and migrates nothing. |
| `test_run` | **Extended, not replaced.** Adds `kind`, `status`, `suite_id`, `assigned_to`, `created_by`, `created_at`, `completed_at`; `started_at` is now nullable. | Every existing row is a CI run: `kind = 'CI'`, `status = 'COMPLETED'` (the defaults), so `POST /ci/test-runs` is unchanged. A `MANUAL` run must have a suite; a `CI` run must be `COMPLETED` (both are database checks). A manual run is created `PLANNED` with `started_at` NULL. |
| `test_run_case`, `test_run_step` | The **snapshot** taken when a run is created: each case's key, title and description, and each step's action and expected result. | Copies, not references. `test_run_case.test_case_id` records where a copy came from but is deliberately not a foreign key, so deleting or editing the live case changes nothing in the run. Results are recorded on these rows (VYB-0924a, below). |

`test_run.suite_id` has no delete action: a suite (or a plan, through its suites) that has been run cannot be deleted from under its runs. The service refuses with a 409 and says how many runs.

## Rules decided for this row (not in the specification; flagged in the session log)

1. A step needs both an action and an expected result. A step with nothing to compare against cannot be judged.
2. Plans have no status. Deleting a plan or suite is allowed only while it has never been run.
3. Replacing a suite's cases, or a case's steps, is one atomic call with the whole ordered list; a refused list changes nothing.
4. A suite with no cases cannot be run.
5. The snapshot itself does not bind to a requirement revision; starting the run does (VYB-0925, below).

## API

Reads need only a signed-in person. Every write is the matrix's **Verify** column (`AccessRule.VERIFY`, scope `ANYWHERE`), the same rule test cases use; the matrix has no column of its own for test planning and Verify is the nearest.

| Method and path | Does |
|---|---|
| `POST/GET /api/v1/test-plans`, `GET/PUT/DELETE /api/v1/test-plans/{id}` | Plans; list filters `applicationId`, `releaseId` |
| `POST/GET /api/v1/test-plans/{planId}/suites` | Suites of a plan |
| `GET/PUT/DELETE /api/v1/test-suites/{id}` | One suite |
| `GET/PUT /api/v1/test-suites/{id}/cases` | A suite's ordered cases; `PUT` replaces them with `{"testCaseIds": [...]}` |
| `GET/PUT /api/v1/test-cases/{id}/steps` | A case's steps; `PUT` replaces them with `{"steps": [{"action", "expectedResult"}]}`, an empty list clears |
| `POST /api/v1/test-suites/{suiteId}/runs` | Create a PLANNED manual run (optional `assignedTo`, `buildLabel`); returns the run and its snapshot |
| `POST /api/v1/test-runs/{id}/start`, `PUT .../steps/{stepId}/result`, `PUT .../cases/{caseId}/result`, `POST .../complete` | Execute a run (VYB-0924a, below) |
| `POST .../steps/{stepId}/evidence`, `POST .../cases/{caseId}/evidence` (multipart), `POST /api/v1/test-runs/{id}/retest` | Evidence and retest (VYB-0924b, below) |
| `GET /api/v1/test-runs`, `GET /api/v1/test-runs/{id}` | Manual runs (filters `suiteId`, `planId`, `status`) and one run with its cases and steps. CI runs are not listed. |

## Executing a run (VYB-0924a)

Migration `V041__test_run_results.sql`; service `TestExecutionService`. PLANNED, `start` to IN_PROGRESS, results recorded, `complete` to COMPLETED (final).

- A result is PASS, FAIL or BLOCKED, recorded on a step of the run's snapshot (`test_run_step`) with an **actual result**, who and when. The actual result is required unless the step passed. Recording again while the run is IN_PROGRESS replaces the result; the audit event keeps the earlier one. The database checks that result, who and when are all set or all null.
- A case's result is **derived**, never stored: FAIL if any step failed, else BLOCKED if any was blocked, else NOT_RUN if any step has no result, else PASS. A case with **no steps** is judged on `test_run_case` itself; recording on a case that has steps is refused.
- The run must be started before anything is recorded; it can be completed only when every case has a result; after that nothing can be recorded.
- The run detail returns each step's result, the case results and a summary (total, passed, failed, blocked, not run).
- Execution, evidence and retest never touch `requirement.status`. Verification records are written when a run is **completed** (VYB-0925, below).
- Not decided here: any Tester may execute any run (`assigned_to` is informational), and there is no cancel.

## Evidence and retest (VYB-0924b)

Migration `V042__test_run_evidence_and_retest.sql`.

**Evidence** reuses the requirement attachment store. `test_run_evidence` links one exact `attachment_version` to a run step (or to a run case that has no steps; the database requires exactly one).
- The file is stored as an attachment of a requirement the step's test case verifies (a `TEST --VERIFIES--> REQUIREMENT` link). One verified requirement: `requirementId` may be omitted. Several: it must be named. None, or a requirement the case does not verify: refused (409).
- The attachment filename is prefixed `run-<8 chars of run id>-<case key>-step<n>-`. **It appears in that requirement's file list** (the cost of reusing attachments). Existing attachment rules (size, type, name) apply.
- The link is to the version, so uploading the same name again adds version 2 and leaves what an earlier result pointed at unchanged. Download uses the existing requirement attachment endpoints.
- Only while the run is IN_PROGRESS; a case with steps takes evidence on its steps. Evidence cannot be removed. Deleting the requirement deletes its attachments and so this evidence.

**Retest** `POST /test-runs/{id}/retest` makes a new PLANNED run (`test_run.retest_of` = the source) of the **failed and blocked cases** of a COMPLETED run, copied from its snapshot (not the live cases), with every result and evidence blank. Cases are renumbered from 1; the suite is kept; the build label is the source's unless given. Refused while an earlier retest of the same run is open; a retest of a retest is allowed. The original run is never changed.

## Verification records (VYB-0925)

Migration `V043__test_run_requirement_revisions.sql`.

- **Starting** a run freezes, per case, the requirements it verifies (the `TEST --VERIFIES--> REQUIREMENT` links) and each requirement's revision at that moment, in `test_run_case_requirement`.
- **Completing** the run writes one `verification` row per case and frozen requirement, bound to the **frozen** revision (what the tester saw), in the same transaction: a PASS case writes PASS, a FAIL case writes FAIL, a BLOCKED case writes none (not tested). The test case id is kept only while the live case still exists. The affected requirements are rescanned by the detectors.
- If the requirement is edited during or after the run, `requirement_verification_state` marks the evidence stale by the existing revision comparison; the run detail shows `testedRevision` against `currentRevision` for each requirement.
- A case that verifies no requirement writes nothing. A retest binds to the revision at its own start; the earlier FAIL stays on record.
- **A manual PASS verifies a requirement** the same way a CI PASS does: `is_verified` is "any PASS at the current revision" (V001). A FAIL does not cancel a PASS from another case or run; that predicate is unchanged.
- `requirement.status` is never written (CLAUDE.md rule 3).

## Audit events

`test-plan.created|updated|deleted`, `test-suite.created|updated|deleted|cases-set`, `test-step.set` (on the test case), `test-run.created|started|step-recorded|case-recorded|completed|evidence-added|retest-created`.

## Tests

`TestManagementIT` (`VYB0923_AC1` to `AC7`), `TestExecutionIT` (`VYB0924_AC1` to `AC6`), `TestEvidenceRetestIT` (`VYB0924b_AC1` to `AC6`), `TestVerificationIT` (`VYB0925_AC1` to `AC6`), `AccessPolicyTest` (nine new write endpoints, all Verify), `ForeignKeyIndexIT`.
