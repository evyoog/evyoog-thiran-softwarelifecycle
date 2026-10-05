# Test management: plans, suites, steps and runs

Added by VYB-0923 (Phase 6, Sprint 5, F14). Migration `database/migrations/V040__test_plans_suites_runs_steps.sql`, schema `vyg_requirement`. Service: `com.vyoog.evidence.TestManagementService`; endpoints: `TestManagementController`.

This row is the **entities and the creation of a run**. Recording a result against a step is VYB-0924, turning results into verification records bound to a requirement revision is VYB-0925, raising a defect from a failed step is VYB-0926 and the Quality screen is VYB-0927. Nothing here records a result, writes a `verification` row, or touches `requirement.status` (CLAUDE.md rule 3).

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
| `test_run_case`, `test_run_step` | The **snapshot** taken when a run is created: each case's key, title and description, and each step's action and expected result. | Copies, not references. `test_run_case.test_case_id` records where a copy came from but is deliberately not a foreign key, so deleting or editing the live case changes nothing in the run. No result columns yet (VYB-0924). |

`test_run.suite_id` has no delete action: a suite (or a plan, through its suites) that has been run cannot be deleted from under its runs. The service refuses with a 409 and says how many runs.

## Rules decided for this row (not in the specification; flagged in the session log)

1. A step needs both an action and an expected result. A step with nothing to compare against cannot be judged.
2. Plans have no status. Deleting a plan or suite is allowed only while it has never been run.
3. Replacing a suite's cases, or a case's steps, is one atomic call with the whole ordered list; a refused list changes nothing.
4. A suite with no cases cannot be run.
5. The snapshot does not bind to a requirement revision; that is VYB-0925, which creates the verification records.

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
| `GET /api/v1/test-runs`, `GET /api/v1/test-runs/{id}` | Manual runs (filters `suiteId`, `planId`, `status`) and one run with its cases and steps. CI runs are not listed. |

## Audit events

`test-plan.created|updated|deleted`, `test-suite.created|updated|deleted|cases-set`, `test-step.set` (on the test case), `test-run.created`.

## Tests

`TestManagementIT` (`VYB0923_AC1` to `AC7`), `AccessPolicyTest` (nine new write endpoints, all Verify), `ForeignKeyIndexIT`.
