# Network calls and database transactions

Added by VYB-0940 (Phase 6, Sprint 8, F31). Migration `database/migrations/V051__resumable_extraction.sql`. Code: `com.vyoog.platform.tx` (`NetworkCallGuard`, `AfterCommitRunner`), `com.vyoog.importqueue` (`ExtractionProgress`, `JdbcExtractionProgress`, `ExtractionSteps`), `DocumentAnalysisService.run(..., steps)`. Decision: D33.

## The rule

**A call over the network (a model provider, object storage) is never made while a database transaction is open.** A transaction holds a pooled connection and its locks for as long as the call takes (seconds to minutes for a model; the pool is 20), and a rollback cannot take back what the call did.

`NetworkCallGuard.beforeNetworkCall(what)` is asked before such a call. If a transaction is open on the thread:

| Mode (`vyoog.network-calls.in-transaction`, env `NETWORK_CALLS_IN_TRANSACTION`) | What happens |
|---|---|
| `warn` (default, production) | The call goes ahead, an ERROR is logged with a stack trace naming it, and the counter `network.calls.in-transaction` goes up. A path that was missed behaves as it always did, and is visible. |
| `fail` (set for the tests) | The call is refused with an `IllegalStateException`. A path that puts a call back inside a transaction fails the build. |

Anything else is refused at startup. The guard is asked by `MeteredModelGateway` (every model call: chat and embedding; nothing is asked when AI is not configured, as nothing is sent) and by `AttachmentService.upload`.

Work run after a commit runs on the enrichment executor (`AfterCommitRunner`). If that executor's queue is full it runs in the committing thread, where Spring still reports the transaction as active although it has committed; `NetworkCallGuard.outsideTransaction` marks such work so it is not flagged.

## What was moved

| Path | Before | Now |
|---|---|---|
| **AI extraction** (`ImportService.extractCandidates`) | One transaction around every chunk read, the summary, the check and the briefs. | No transaction around the calls. Each finished step is saved; the candidates, the description and the batch's state are written in one short transaction at the end. See below. |
| **Re-analysis** (`DocumentAnalysisService.analyse`) | One transaction around the run. | The run is outside; only saving it is a transaction. |
| **Candidate check** (`ImportService.lint`) | The duplicate check embedded the statement inside the transaction. | The embedding is made first; the result is written in a short transaction. |
| **Trace proposals** (`ImportService.proposeTraceLinks`) | The nearest-neighbour search and the classifier ran inside it. | Both run outside; only writing the proposal is a transaction. |
| **Enrichment after a write** (`RequirementEnrichmentService.enrich`) | `@Async` and `@Transactional`: the detectors and the embedding ran in one transaction. | No transaction of its own; each write inside (a finding reconciled, an embedding stored) is its own. |
| **A requirement revision** (`RequirementService`) | Embedded the new statement inside the revision's transaction. | The embedding is made after the commit (`RequirementEnrichmentService.embed`). |
| **Rescans from write paths** (`DetectionSweepService.rescanObject`, called from requirement, criterion, trace link, test case, test run and commit writes) | Every detector ran inside the caller's transaction, including the two that call a model (`ConflictingRequirementsDetector`, `UnmappedControlClauseDetector`). | A detector that says `callsModel()` is run after the commit instead; the others run in place as before. |
| **Test-run evidence** (`TestExecutionService.addStepEvidence`, `addCaseEvidence`; found by the guard in `TestEvidenceRetestIT`) | One transaction around the file's upload to object storage and the evidence row. | What is read before and written after are each one short transaction; the file is stored between them (`AttachmentService.storeFile`, then `attach` inside the second, `discard` if it fails). The run is checked again in the second. |
| **Attachment upload** (`AttachmentService.upload`) | The file was put to object storage inside the transaction; a rollback left it behind. | The file is stored first under a fresh key (`requirements/<requirement>/<uuid>-<name>`), then the rows are written in one short transaction; if that fails the file is deleted again. |

The nightly and manual sweeps were already outside any transaction (each detector's candidates are computed, then reconciled in the reconciler's own transaction).

## Resumable extraction

An extraction by meaning is many calls: one per chunk of the document, the description, its check (and, if the check finds a claim unsupported, a second description and check), and one per batch of six briefs. It used to be all-or-nothing.

Now each finished call is a **step**, saved in `import_extraction_step` as it completes (its own transaction, so it survives a later failure): key (`triage:<chunk>`, `synthesis:0`, `critique:0`, `synthesis:1`, `critique:1`, `briefs:<first finding>`), the model's reply as JSON, and `source_digest`, a SHA-256 of the file name and text. When extraction is made again:

- a step whose digest matches is **reused**: no model call, no share of the per-run call budget, not counted in the run's call count;
- a step made from other text (the document was changed) is deleted, and everything is read again;
- a saved step that cannot be read back is made again, never guessed;
- the result is what an uninterrupted run would have produced (tested).

Where the batch stands is on `import_batch.state`:

| State | Meaning |
|---|---|
| `UPLOADED` | Nothing done yet. |
| `EXTRACTING` | One extraction is running (`extraction_started_at`). A second request is refused (409, "already being extracted"). A claim older than 30 minutes is taken over, because the instance that held it is gone. |
| `EXTRACTION_FAILED` | The last attempt stopped; `extraction_error` says why (a provider failure, the token budget, a document with nothing in it). Nothing half-made is shown: no candidates and no description. The finished steps are kept. |
| `EXTRACTED` | Done. The steps are deleted in the same transaction that writes the candidates. |

A batch that was already EXTRACTED and is extracted again goes back to EXTRACTED if that fails. The Import screen shows "Continue extraction" for a failed batch and the stop reason in words.

Not covered: a structural extraction (no model configured) and the PRD template path make no model call, so they are one short transaction and keep no steps.

## Not here

- `ConnectorExecutor` (outbound connector calls) does not ask the guard. Its callers were moved out of transactions in VYB-0916; nothing enforces it.
- The Keycloak and single-sign-on clients are not guarded; they are not called from inside a transaction.
- The guard sees only a transaction open **on the calling thread**.
- A step is reused even if the prompt that made it has since changed; it is tied to the text, not to the prompt version.
- Steps of a batch nobody continues stay until the batch is deleted (the delete cascades).
- Model-backed findings from a write now appear a moment after it (the executor), not within the request. Nothing waits for them.

## Tests

`NetworkCallGuardTest` (`VYB0940_AC15` to `AC22`), `ResumableAnalysisTest` (`AC1` to `AC7`), `ResumableExtractionTest` (`AC8` to `AC14`), `DeferredModelDetectionTest` (`AC23` to `AC25`), `AttachmentUploadTransactionTest` (`AC26` to `AC31`, `AC45`), `ResumableExtractionIT` (`AC32` to `AC40`, real PostgreSQL and a stub provider, the guard refusing), `extractionState.test.ts` (`AC41` to `AC44`).
