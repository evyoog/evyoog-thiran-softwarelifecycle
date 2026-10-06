# Quality screen: test runs and pass rate

Added by VYB-0927 (Phase 6, Sprint 5, F14). Frontend: `frontend/src/features/quality/` (`TestRunsTab.tsx`, `RunDetailView.tsx`, `PassRatesTab.tsx`, `testRuns.ts`). Data model and rules: [`docs/07-database/data-model/test-management.md`](../../07-database/data-model/test-management.md).

The Quality screen (§7.7) gains two tabs beside Verification, Dependencies, Defects and Test cases.

## Test runs

- **Runs** (default): manual runs, newest first, filterable by status; each row shows plan and suite, build, status, number of cases, who it is assigned to. Opening one shows the run.
- **Plans**: each plan with its application, suites and run count; expanding a plan lists its suites, each with a **New run** button (a suite with no cases cannot be run). Creating or editing plans, suites and steps, and attaching evidence, are not on this screen: they stay in the API.
- **A run**: status, who it is assigned to, when it was created, started and completed, and the summary (passed, failed, blocked, not run). Each case shows its derived result and the requirements it verifies at the revision frozen when the run started, with a note when the requirement has been edited since. Each step shows its action, expected result, result, actual result, who recorded it and when, any evidence (listed, and saved through the requirement's attachment endpoint), and the defect raised from it.

## Who can do what

Everything above is readable by any signed-in person. The buttons that change anything (Start run, the Passed / Failed / Blocked buttons, Complete run, Retest, Raise a defect, New run) show only for a Tester or a platform administrator, from the person's grants. This is only whether to show them: the server enforces the Verify rule on every write and a refusal is shown in words if it ever comes back.

## Executing a run

- **Start run** (planned runs). Nothing can be recorded before it.
- **Passed** saves at once. **Failed** and **Blocked** ask "what actually happened" first and refuse an empty answer, as the server does. Recording again while the run is in progress replaces the result. A case with no steps is judged on the case itself.
- **Complete run** is enabled only when every case has a result; otherwise it says how many still have none. Completing is final and records verification evidence for the requirements the cases cover, bound to the revision each had when the run started. It never changes a requirement's status.
- **Retest failed and blocked** (completed runs with something failed or blocked) makes a new planned run of just those cases, copied from this run, and opens it.
- **Raise a defect** (a failed step, or a failed case with no steps; once per failure): a modal prefilled with the title `<case key> step <n> failed: <action>`, severity MEDIUM, found in QA and the requirement (chosen between if the case verifies several), with the test, step, expected, actual, plan, suite and build shown beneath. Anything may be changed before raising.

## Pass rate

One row per requirement that has a test case, worst first, searchable by key or title. Each test case counts once, by its **latest** result at the requirement's **current** revision, CI and manual runs together.

- **Pass rate** = passed out of the cases that have a result at the current revision. When none has, it reads **Not run**, never 0%.
- A case with no result at all is **not run**; a case whose only results are from before the requirement last changed is **stale**. Neither is a failure, and both are named in the breakdown.
- The "N of M cases have a result at this revision" line says what the rate rests on.
- A failure that a later run passed no longer counts, because only the latest result does. A blocked case writes no result and counts as not run.

Backend: `GET /api/v1/quality/pass-rates?q=&page=&size=` (any signed-in person), derived on every call from the `verification` rows; nothing is stored.

## Conventions kept

- A result or state is always a **label and a glyph** as well as a colour (passed ✓, failed ✕, blocked ⊘, not run ○; run states ○ ◐ ●). The colours are the existing OK, critical, info and neutral tokens; **never the amber token** (amber means AI). The contrast test covers the new badge classes in both themes.
- Absence is said in words (Not run, no result yet, no one assigned), never blank or zero.
