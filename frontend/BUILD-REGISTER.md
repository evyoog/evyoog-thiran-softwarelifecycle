# Vyoog build register

Every requirement, one line. Text is generated just-in-time, per session.
A commit without a `Requirement:` trailer fails CI.

| ID | Phase | Capability | One-line requirement | Status | Session |
|---|---|---|---|---|---|
| VYB-0001 | 0 | Build | Maven multi-module reactor with four modules | DONE | 1 |
| VYB-0002 | 0 | Tenancy | ~~Bind `app.tenant_id` with SET LOCAL at connection checkout~~ | SUPERSEDED | 2 |
| VYB-0003 | 0 | Tenancy | ~~Unbound query returns zero rows, not all rows~~ | SUPERSEDED | 2 |
| VYB-0004 | 0 | Tenancy | ~~A tenant sees only its own rows~~ | SUPERSEDED | 2 |
| VYB-0005 | 0 | Tenancy | ~~Writing into another tenant is refused by WITH CHECK~~ | SUPERSEDED | 2 |
| VYB-0006 | 0 | Tenancy | ~~Hibernate `@TenantId` populates the column on insert~~ | SUPERSEDED | 2 |
| VYB-0007 | 0 | Identity | Validate Keycloak JWT: issuer, signature, and (when configured) audience | DONE | 13 |
| VYB-0048b | 0 | Identity (FE) | Username/password sign-in screen, server-mediated ROPC, no secret in the frontend | DONE | 18 |
| VYB-0008 | 0 | Identity | ~~Resolve tenant from the token claim only~~ | SUPERSEDED | 2 |
| VYB-0009 | 0 | Identity | Upsert `app_user` on first sight of a subject | DONE | 1 |
| VYB-0010 | 0 | Schema | Flyway baseline applies to the `vyg_requirement` schema | DONE | 2 |
| VYB-0011 | 0 | Architecture | ArchUnit: domain must not depend on web | DONE | 1 |
| VYB-0012 | 0 | Architecture | ~~ArchUnit: every entity is TenantOwned~~ | SUPERSEDED | 2 |
| VYB-0013 | 0 | Shell | Sidebar renders ten modules with a divider before Administration | DONE | 1 |
| VYB-0014 | 0 | Shell | Dark and light themes, persisted | DONE | 1 |
| VYB-0015 | 0 | Shell | OIDC Authorization Code + PKCE, token held in memory | DONE | 1 |
| VYB-0016 | 0 | Portfolio | Create and list products through the API | DONE | 1 |
| VYB-0100 | 1 | Portfolio | Product/application/capability hierarchy, one parent each | DONE | 3 |
| VYB-0101 | 1 | Portfolio | Create, read, update and archive at each level | DONE | 3,8 |
| VYB-0102 | 1 | Portfolio | Glossary terms with definition, owner, usage | DONE | 8 |
| VYB-0103 | 1 | Portfolio | Conflicting glossary definitions detected | DONE | 8 |
| VYB-0788 | 1 | Portfolio (FE) | Card-grid product dashboard: real counts, mark/vertical/owner/lifecycle, CSV report | DONE | 17,21 |
| VYB-0110 | 1 | Requirements | Requirement aggregate (key, capability, type, status…) | DONE | 3 |
| VYB-0111 | 1 | Requirements | Keys allocated without collision | DONE | 3 |
| VYB-0112 | 1 | Requirements | Revisions are immutable | DONE | 3 |
| VYB-0113 | 1 | Requirements | Unchanged saves do not create revisions | DONE | 3 |
| VYB-0114 | 1 | Requirements | Acceptance criteria are ordered children | DONE | 3 |
| VYB-0115 | 1 | Requirements | State machine rejects illegal transitions | DONE | 3 |
| VYB-0116 | 1 | Requirements | Submission requires substance or an override reason | DONE | 3 |
| VYB-0117 | 1 | Requirements | Verification is a predicate, never a flag | DONE | 3,9 |
| VYB-0118 | 1 | Requirements | A revision bump demotes a verified requirement | DONE | 3 |
| VYB-0119 | 1 | Requirements | Lifecycle history recorded per stage | DONE | 13 |
| VYB-0120 | 1 | Requirements | Optimistic concurrency (409 with both revisions) | DONE | 3 |
| VYB-0121 | 1 | Requirements | Bulk edit is per-row | DONE | 8 |
| VYB-0122 | 1 | Requirements | Bulk edit is one undoable action | DONE | 8 |
| VYB-0123 | 1 | Requirements | Attachments are versioned, never overwritten | DONE | 8,16 |
| VYB-0124 | 1 | Requirements | Comments | DONE | 8 |
| VYB-0125 | 1 | Requirements | Audit event per state change | DONE | 3 |
| VYB-0130 | 1 | API | List with filter, sort and page | DONE | 3,8 |
| VYB-0131 | 1 | API | Server-side grid contract (50k rows, 300ms) | DONE | 15 |
| VYB-0132 | 1 | API | Idempotent creation | DONE | 8 |
| VYB-0133 | 1 | API | Transition endpoint (target state + reason) | DONE | 3 |
| VYB-0134 | 1 | API | Lint endpoint (no persistence) | DONE | 5 |
| VYB-0135 | 1 | API | Similarity endpoint (trigram) | DONE | 8 |
| VYB-0140 | 1 | Trace graph | Typed trace links | DONE | 4 |
| VYB-0141 | 1 | Trace graph | Links record the revision they were reviewed at | DONE | 4,5 |
| VYB-0142 | 1 | Trace graph | Closure table maintained transactionally | DONE | 4 |
| VYB-0143 | 1 | Trace graph | Closure is rebuildable and checked | DONE | 4 |
| VYB-0144 | 1 | Trace graph | Traversal terminates on cyclic graphs | DONE | 4 |
| VYB-0145 | 1 | Trace graph | Upstream and downstream queries, with path | DONE | 4 |
| VYB-0146 | 1 | Trace graph | Coverage projection, in the list response | DONE | 4 |
| VYB-0147 | 1 | Trace graph | Coverage matrix (requirement × test) | DONE | 13 |
| VYB-0150 | 1 | Detection | Detector contract: pure functions, write nothing | DONE | 5 |
| VYB-0151 | 1 | Detection | Findings reconcile by fingerprint | DONE | 5 |
| VYB-0152 | 1 | Detection | Disappeared findings auto-resolve | DONE | 5 |
| VYB-0153 | 1 | Detection | Dismissal requires a reason and an actor | DONE | 5 |
| VYB-0154 | 1 | Detection | Dismissal survives until the object's revision changes | DONE | 5 |
| VYB-0155 | 1 | Detection | Detector — missing verification (noverify) | DONE | 5 |
| VYB-0156 | 1 | Detection | Detector — orphan | DONE | 5 |
| VYB-0157 | 1 | Detection | Detector — no downstream design (nodesign) | DONE | 5 |
| VYB-0158 | 1 | Detection | Detector — no acceptance criteria (noac) | DONE | 5 |
| VYB-0159 | 1 | Detection | Detector — suspect link | DONE | 5 |
| VYB-0160 | 1 | Detection | Detector — unmeasurable wording (ambig) | DONE | 13 |
| VYB-0161 | 1 | Detection | Detection triggers on change, bounded | DONE | 8 |
| VYB-0162 | 1 | Detection | Nightly full sweep, idempotent, counts emitted | DONE | 5 |
| VYB-0163 | 1 | Detection | Sweeps do not overlap; manual rescan rate-limited | DONE | 5 |
| VYB-0164 | 1 | Detection | Rules are configurable (enable/disable/tune) | DONE | 13 |
| VYB-0170–0180 | 1 | Grid | Virtualisation, column vis/reorder/resize/pin, sort, filters, grouping, density, selection, inline edit, bulk edit | DONE | 8,16 |
| VYB-0181 | 1 | Grid | Coverage pips, letter-coded | DONE | 6 |
| VYB-0182–0183 | 1 | Grid | Undo toast; confirm before consequential actions | DONE | 8 |
| VYB-0190 | 1 | Detail panel | Attributes and statement, with a local draft | DONE | 6 |
| VYB-0191 | 1 | Detail panel | Acceptance criteria: add, edit, reorder, remove | DONE | 13 |
| VYB-0192 | 1 | Detail panel | Traceability display | DONE | 13 |
| VYB-0193 | 1 | Detail panel | Lifecycle timeline | DONE | 13 |
| VYB-0194 | 1 | Detail panel | Concurrent edit is surfaced, never silent | DONE | 13 |
| VYB-0195 | 1 | Detail panel | Attachments | DONE | 13 |
| VYB-0200 | 1 | Authoring | Cascading placement (product → app → capability) | DONE | 6 |
| VYB-0201 | 1 | Authoring | Live lint while typing | DONE | 6 |
| VYB-0202–0203 | 1 | Authoring | Live gap preview, live quality score | DONE | 13 |
| VYB-0204 | 1 | Authoring | Duplicate detection (trigram similarity while typing) | DONE | 8 |
| VYB-0205 | 1 | Authoring | Save paths (draft / add-another / submit) | DONE | 13 |
| VYB-0206 | 1 | Authoring | Confirmation is targeted, not routine | DONE | 13 |
| VYB-0210–0213 | 1 | Documents | Register, prose view, Word/ReqIF export, coverage matrix view | DONE | 13 |
| VYB-0215 | 1 | Documents | Trace graph view (need→requirement→design→code→test) | DONE | 15 |
| VYB-0214 | 1 | Documents | Suspect links view, oldest first, opens a review | DONE | 7 |
| VYB-0220 | 1 | Analytics | Findings list, filterable | DONE | 13 |
| VYB-0221 | 1 | Analytics | Accept and dismiss, reason required for dismissal | DONE | 6 |
| VYB-0222 | 1 | Analytics | Lifecycle coverage spine | DONE | 15 |
| VYB-0223 | 1 | Analytics | Rules screen (list, disable) | DONE | 13 |
| VYB-0224 | 1 | Analytics | Dismissed findings, reopenable | DONE | 7 |
| VYB-0230 | 1 | Home | Derived figures only, no placeholders | DONE | 6 |
| VYB-0231 | 1 | Home | Blocking-this-release list | PARTIAL | 13 |
| VYB-0232 | 1 | Home | Requirement flow, largest drop-off marked stuck | DONE | 7 |
| VYB-0233 | 1 | Home | Not owned here (external systems) | DONE | 13 |
| VYB-0300 | 2 | Review | Review rounds | DONE | 9 |
| VYB-0301 | 2 | Review | Participants and their roles | DONE | 9 |
| VYB-0302 | 2 | Review | Signatures | DONE | 9 |
| VYB-0303 | 2 | Review | Step-up authentication for signing | PARTIAL | 9 |
| VYB-0304 | 2 | Review | Separation of duties on approval | DONE | 9 |
| VYB-0305 | 2 | Review | Service accounts cannot approve | PARTIAL | 9 |
| VYB-0306 | 2 | Review | Review comments | DONE | 9 |
| VYB-0307 | 2 | Review | Blocking clarifications block closure | DONE | 9 |
| VYB-0310 | 2 | Evidence | Test cases | DONE | 9 |
| VYB-0311 | 2 | Evidence | Test runs ingested from CI | DONE | 9 |
| VYB-0312 | 2 | Evidence | Verification binds to a revision | DONE | 9 |
| VYB-0313 | 2 | Evidence | Promotion to verified is systemic | DONE | 9 |
| VYB-0314 | 2 | Evidence | Stale evidence is queryable | DONE | 9 |
| VYB-0315 | 2 | Evidence | Untraced code change detector | DONE | 9 |
| VYB-0316 | 2 | Evidence | Commit trailers create links | DONE | 9 |
| VYB-0320 | 2 | Defects | Defect record | DONE | 9 |
| VYB-0321 | 2 | Defects | Root cause classification | DONE | 9 |
| VYB-0322 | 2 | Defects | Defect routing | DONE | 13 |
| VYB-0323 | 2 | Defects | Requirement-caused defects feed quality reporting | DONE | 9 |
| VYB-0330 | 2 | Clarification | Clarification record | DONE | 9 |
| VYB-0331 | 2 | Clarification | Blocking clarifications block derived tasks | DONE | 9 |
| VYB-0332 | 2 | Clarification | Answers are recorded with an actor | DONE | 9 |
| VYB-0333 | 2 | Clarification | A meaning-changing answer becomes a change request | DONE | 9 |
| VYB-0334 | 2 | Clarification | Ageing clarifications escalate | DONE | 20 |
| VYB-0340 | 2 | Tasks | No task authoring exists | DONE | 9 |
| VYB-0341 | 2 | Tasks | Author task — unmeasurable wording | DONE | 9 |
| VYB-0342 | 2 | Tasks | Reviewer task — review pending | DONE | 9 |
| VYB-0343 | 2 | Tasks | Approver task — awaiting signature | DONE | 9 |
| VYB-0344 | 2 | Tasks | Developer task — implement | DONE | 9 |
| VYB-0345 | 2 | Tasks | Developer task — re-implement | DONE | 9 |
| VYB-0346 | 2 | Tasks | Tester task — verify | DONE | 9 |
| VYB-0347 | 2 | Tasks | Tester task — re-verify | DONE | 9 |
| VYB-0348 | 2 | Tasks | Every task states why it exists | DONE | 9 |
| VYB-0349 | 2 | Tasks | Stalled work is identified | DONE | 9 |
| VYB-0355 | 2 | Notifications | Inbox items | DONE | 9 |
| VYB-0356 | 2 | Notifications | Notifications are events, not polling | DONE | 20 |
| VYB-0357 | 2 | Notifications | Digest rather than flood | DONE | 13 |
| VYB-0360 | 2 | Quality (FE) | Review rounds screen | DONE | 9 |
| VYB-0361 | 2 | Quality (FE) | Signing | DONE | 9 |
| VYB-0362 | 2 | Quality (FE) | Verification screen | DONE | 9 |
| VYB-0363 | 2 | Quality (FE) | Draft test case action | DONE | 15 |
| VYB-0364 | 2 | Quality (FE) | Defects screen | DONE | 9 |
| VYB-0365 | 2 | Quality (FE) | Raise a defect from a requirement | DONE | 9 |
| VYB-0370 | 2 | My Work (FE) | Today | DONE | 9 |
| VYB-0371 | 2 | My Work (FE) | Pipeline | DONE | 9 |
| VYB-0372 | 2 | My Work (FE) | Calendar | DONE | 15 |
| VYB-0373 | 2 | My Work (FE) | Perspective switching | PARTIAL | 9 |
| VYB-0374 | 2 | My Work (FE) | Blocked work shows its blocker | DONE | 9 |
| VYB-0380 | 2 | Clarification (FE) | Raise a clarification | DONE | 9 |
| VYB-0381 | 2 | Clarification (FE) | Answer a clarification | DONE | 9 |
| VYB-0382 | 2 | Clarification (FE) | Clarifications are visible on the requirement | DONE | 9 |
| VYB-0390 | 2 | Change requests | Change request record | DONE | 9 |
| VYB-0391 | 2 | Change requests | Impact computed before decision | DONE | 9 |
| VYB-0392 | 2 | Change requests | Acceptance marks downstream suspect | DONE | 9 |
| VYB-0393 | 2 | Change requests | Change request screen | DONE | 9 |
| VYB-0450 | 3 | Briefs | Brief generation | DONE | 10 |
| VYB-0451 | 3 | Briefs | Three targets that genuinely differ | DONE | 10 |
| VYB-0452 | 3 | Briefs | Developer assigned at generation | DONE | 10 |
| VYB-0453 | 3 | Briefs | Category review section | DONE | 10 |
| VYB-0454 | 3 | Briefs | Requirements grouped by category | DONE | 10 |
| VYB-0455 | 3 | Briefs | Briefs persist with a baseline reference | DONE | 10 |
| VYB-0456 | 3 | Briefs | Staleness is detected | DONE | 10 |
| VYB-0457 | 3 | Briefs | Definition of done is included | DONE | 10 |
| VYB-0460 | 3 | Signals | Eight exact signals | DONE | 10 |
| VYB-0461 | 3 | Signals | Signals carry their computation | DONE | 10 |
| VYB-0462 | 3 | Signals | Comparison against the portfolio median | DONE | 10 |
| VYB-0463 | 3 | Signals | No combined effort figure | DONE | 10 |
| VYB-0464 | 3 | Signals | Impact volume | DONE | 13 |
| VYB-0465 | 3 | Signals | Signals exportable to the delivery tool | DONE | 10,16 |
| VYB-0470 | 3 | Baselines | Baseline freeze | DONE | 10 |
| VYB-0471 | 3 | Baselines | Baseline requires step-up | DONE | 10 |
| VYB-0472 | 3 | Baselines | Baseline diff | DONE | 10 |
| VYB-0473 | 3 | Baselines | Variants | DONE | 10 |
| VYB-0474 | 3 | Releases | Release scope | DONE | 10 |
| VYB-0475 | 3 | Releases | Scope movement is recorded with an actor | DONE | 10 |
| VYB-0476 | 3 | Releases | Release readiness | DONE | 10 |
| VYB-0480 | 3 | Deployment | Environments | DONE | 10 |
| VYB-0481 | 3 | Deployment | Deployment events | DONE | 10 |
| VYB-0482 | 3 | Deployment | Requirement presence per build | DONE | 10 |
| VYB-0483 | 3 | Deployment | Blocked from release | DONE | 10 |
| VYB-0484 | 3 | Deployment | Release notes generation | DONE | 10 |
| VYB-0490 | 3 | Design (BE) | One flow per application | DONE | 10 |
| VYB-0491 | 3 | Design (BE) | Node kinds | DONE | 10 |
| VYB-0492 | 3 | Design (BE) | Nodes link to requirements | DONE | 10 |
| VYB-0493 | 3 | Design (BE) | Design coverage in both directions | DONE | 10 |
| VYB-0494 | 3 | Design (BE) | Edges carry branch labels | DONE | 10 |
| VYB-0495 | 3 | Design (BE) | Flow deletion preserves requirements | DONE | 10 |
| VYB-0500 | 3 | Delivery (FE) | Brief composer | DONE | 10 |
| VYB-0501 | 3 | Delivery (FE) | Target selector | DONE | 10 |
| VYB-0502 | 3 | Delivery (FE) | Developer selection | DONE | 10,16 |
| VYB-0503 | 3 | Delivery (FE) | Brief preview and download | DONE | 10 |
| VYB-0504 | 3 | Delivery (FE) | Stale briefs are marked | DONE | 10 |
| VYB-0505 | 3 | Delivery (FE) | Confirm before pushing to a delivery tool | DONE | 10,16 |
| VYB-0506 | 3 | Delivery (FE) | Scope signal cards | DONE | 10 |
| VYB-0507 | 3 | Delivery (FE) | Borrowed signals look borrowed | DONE | 15 |
| VYB-0508 | 3 | Delivery (FE) | Impact screen | DONE | 10 |
| VYB-0515 | 3 | Releases (FE) | Release scope screen | DONE | 10 |
| VYB-0516 | 3 | Releases (FE) | Scope movement | DONE | 10 |
| VYB-0517 | 3 | Releases (FE) | Baselines screen | DONE | 10 |
| VYB-0518 | 3 | Releases (FE) | Baseline comparison | DONE | 10 |
| VYB-0519 | 3 | Releases (FE) | Variants screen | DONE | 10 |
| VYB-0520 | 3 | Releases (FE) | Deployment screen | DONE | 10 |
| VYB-0521 | 3 | Releases (FE) | Release notes screen | DONE | 10 |
| VYB-0530 | 3 | Design (FE) | Product then application selection | DONE | 10 |
| VYB-0531 | 3 | Design (FE) | Automatic layout | DONE | 10 |
| VYB-0532 | 3 | Design (FE) | Layout terminates on cyclic graphs | DONE | 10 |
| VYB-0533 | 3 | Design (FE) | Node shape conveys kind | DONE | 10 |
| VYB-0534 | 3 | Design (FE) | Node colour derives from its requirements | DONE | 10 |
| VYB-0535 | 3 | Design (FE) | Node inspector | DONE | 10 |
| VYB-0536 | 3 | Design (FE) | Add a step | DONE | 10 |
| VYB-0537 | 3 | Design (FE) | Coverage tab | DONE | 10,16 |
| VYB-0538 | 3 | Design (FE) | Diagram export | DONE | 10 |
| VYB-0600 | 4 | Embeddings | Vector storage records model and revision | DONE | 11 |
| VYB-0601 | 4 | Embeddings | Re-embed on statement change, idempotent per revision | DONE | 11 |
| VYB-0602 | 4 | Embeddings | Indexed similarity search | PARTIAL | 11 |
| VYB-0603 | 4 | Embeddings | Embedding failures degrade gracefully | DONE | 11 |
| VYB-0604 | 4 | Embeddings | Model change re-embeds the corpus; cross-model comparison refused | DONE | 11 |
| VYB-0610 | 4 | AI detectors | Detector — duplicate across applications | DONE | 11 |
| VYB-0611 | 4 | AI detectors | Detector — unmapped control clause | DONE | 11 |
| VYB-0612 | 4 | AI detectors | Detector — conflicting requirements (LLM-adjudicated) | DONE | 20 |
| VYB-0613 | 4 | AI detectors | Detector — happy path only | DONE | 11 |
| VYB-0614 | 4 | AI detectors | Detector — missing non-functional counterpart | DONE | 11 |
| VYB-0615 | 4 | AI detectors | Confidence recorded and thresholded | DONE | 11 |
| VYB-0616 | 4 | AI detectors | Model provenance on every AI finding | DONE | 11 |
| VYB-0617 | 4 | AI detectors | False-positive rate measured from real dismissals | DONE | 11 |
| VYB-0618 | 4 | AI detectors | Noisy detectors default off for new tenants | PARTIAL | 11 |
| VYB-0619 | 4 | AI detectors | No AI output is applied automatically | DONE | 11 |
| VYB-0620 | 4 | AI detectors | AI cost and volume are bounded per run | DONE | 13 |
| VYB-0630 | 4 | Import queue | Document upload, nothing enters the register | DONE | 11 |
| VYB-0631 | 4 | Import queue | Upload kinds distinguished; standard docs validated strictly | DONE | 11 |
| VYB-0632 | 4 | Import queue | Candidate extraction, repeatable, source-located | DONE | 11 |
| VYB-0633 | 4 | Import queue | Candidates linted before acceptance | DONE | 11 |
| VYB-0634 | 4 | Import queue | Capability inference is a proposal, confirmation required | DONE | 11 |
| VYB-0635 | 4 | Import queue | Candidate text editable before import | DONE | 11 |
| VYB-0636 | 4 | Import queue | Selective commit, as drafts | DONE | 11 |
| VYB-0637 | 4 | Import queue | Duplicate candidates flagged against the register | DONE | 11 |
| VYB-0638 | 4 | Import queue | Migration import: ReqIF and spreadsheet, preserving identifiers/links | DONE | 13 |
| VYB-0666 | 4 | Import queue | Word (.docx) import; AI-proposed requirement type, confirmation required | DONE | 19 |
| VYB-0667 | 4 | Import queue | Multi-agent document analysis: relevance triage, synthesis, grounding critique; description is a proposal | DONE | 22 |
| VYB-0668 | 4 | Import queue (FE) | Document analysis panel: description, evidence, coverage and unsupported claims, accept/dismiss | DONE | 22 |
| VYB-0650 | 4 | Analytics (FE) | AI findings render in the reserved amber treatment, with confidence | DONE | 11 |
| VYB-0651 | 4 | Analytics (FE) | Provenance (model, prompt version) is inspectable | DONE | 11 |
| VYB-0652 | 4 | Analytics (FE) | Rule tuning: enable/disable/threshold, effect previewed first | DONE | 12 |
| VYB-0653 | 4 | Analytics (FE) | False-positive reporting: rate + top dismissal reasons | DONE | 11 |
| VYB-0654 | 4 | Analytics (FE) | Duplicate finding shows both requirements side by side | DONE | 11 |
| VYB-0655 | 4 | Analytics (FE) | Conflict finding explains itself | DONE | 20 |
| VYB-0660 | 4 | Import queue (FE) | Upload and progress; a failed parse names the cause | DONE | 11 |
| VYB-0661 | 4 | Import queue (FE) | Candidate list; attention-needed distinguishable at a glance | DONE | 11 |
| VYB-0662 | 4 | Import queue (FE) | Inline candidate text editing, source document untouched | DONE | 11 |
| VYB-0663 | 4 | Import queue (FE) | Per-candidate decision (accept/fix/as-written/skip) | DONE | 13 |
| VYB-0664 | 4 | Import queue (FE) | Capability confirmation gate before commit | DONE | 11 |
| VYB-0665 | 4 | Import queue (FE) | Batch summary before commit, not after | DONE | 11 |
| VYB-0700 | 5 | Users and access | User directory: source, auth state, last activity, status | DONE | 12 |
| VYB-0701 | 5 | Users and access | Role by scope, resolved by walking the hierarchy upward | DONE | 12 |
| VYB-0702 | 5 | Users and access | Grants expire; mandatory and capped for external users | DONE | 12 |
| VYB-0703 | 5 | Users and access | Revocation takes effect on the next request | DONE | 12 |
| VYB-0704 | 5 | Users and access | Separation-of-duties findings from the grant model itself | DONE | 12 |
| VYB-0705 | 5 | Users and access | Departed accounts still holding a grant are reported | DONE | 12 |
| VYB-0706 | 5 | Users and access | Delegation while on leave | DONE | 13 |
| VYB-0710 | 5 | Service accounts | Registry: purpose, scopes, key age, last use | DONE | 12 |
| VYB-0711 | 5 | Service accounts | Least privilege by default; scopes validated | DONE | 12 |
| VYB-0712 | 5 | Service accounts | Key rotation with an overlap window | PARTIAL | 12 |
| VYB-0713 | 5 | Service accounts | Stale keys reported past a configurable age | DONE | 12 |
| VYB-0720 | 5 | Audit | Append-only audit; the store itself refuses update/delete | DONE | 12 |
| VYB-0721 | 5 | Audit | Actor, action, object, before/after, timestamp, request id, origin | DONE | 12 |
| VYB-0722 | 5 | Audit | Query by actor, action, object and time window, paged | DONE | 12 |
| VYB-0723 | 5 | Audit | Retention is configurable, past-retention partitions archive | DONE | 12,16 |
| VYB-0730 | 5 | Tenant lifecycle | Provisioning: rules, default roles, first administrator | DONE | 13 |
| VYB-0731 | 5 | Tenant lifecycle | Suspension: authentication succeeds, access refused | DONE | 12 |
| VYB-0732 | 5 | Tenant lifecycle | Export: entire dataset, self-describing | DONE | 20 |
| VYB-0733 | 5 | Tenant lifecycle | Deletion: soft then hard, no orphans (reframed — see session 15) | DONE | 15 |
| VYB-0734 | 5 | Tenant lifecycle | Per-tenant configuration: prefix, stage thresholds, detector defaults | DONE | 12 |
| VYB-0740 | 5 | Integrations | Connection registry: owner, direction, state | DONE | 12 |
| VYB-0741 | 5 | Integrations | Webhook authentication and replay protection | DONE | 12 |
| VYB-0742 | 5 | Integrations | Outbound push only on an explicit action | DONE | 12 |
| VYB-0743 | 5 | Integrations | Integration failure surfaced, not retried silently | DONE | 12 |
| VYB-0750 | 5 | Administration (FE) | Users screen | DONE | 12 |
| VYB-0751 | 5 | Administration (FE) | Grants screen: add/revoke, hierarchy-following scope picker | DONE | 12 |
| VYB-0752 | 5 | Administration (FE) | Roles matrix, generated from the authorisation model | DONE | 12 |
| VYB-0753 | 5 | Administration (FE) | Service accounts screen, stale keys flagged | DONE | 12 |
| VYB-0754 | 5 | Administration (FE) | Security screen with a corrective action per finding | DONE | 12 |
| VYB-0755 | 5 | Administration (FE) | Audit log screen; no delete action anywhere | DONE | 12 |
| VYB-0756 | 5 | Administration (FE) | Connected systems screen, counts derived not hardcoded | DONE | 12 |
| VYB-0757 | 5 | Administration (FE) | Settings screen | DONE | 12,16 |
| VYB-0758 | 5 | Administration (FE) | Consistent confirmation on consequential actions | DONE | 12,16 |
| VYB-0765 | 5 | Cross-cutting (FE) | Command palette, generated from the module list | DONE | 12 |
| VYB-0766 | 5 | Cross-cutting (FE) | Global search across four kinds, grouped | DONE | 12 |
| VYB-0767 | 5 | Cross-cutting (FE) | Keyboard-operable grid | DONE | 12,16 |
| VYB-0768 | 5 | Cross-cutting (FE) | Focus trapped in every modal, restored on close | DONE | 12 |
| VYB-0769 | 5 | Cross-cutting (FE) | Toasts and async results announced to assistive tech | DONE | 12 |
| VYB-0770 | 5 | Cross-cutting (FE) | Colour is never the only signal | DONE | 12,16 |
| VYB-0771 | 5 | Cross-cutting (FE) | Empty states explain, never blank | DONE | 12 |
| VYB-0780 | 5 | Performance | Grid serves 50,000 requirements within budget | DONE | 15 |
| VYB-0781 | 5 | Performance | Detection sweep completes within the nightly window at scale | DONE | 20 |
| VYB-0782 | 5 | Performance | Rate limiting on expensive operations | DONE | 12 |
| VYB-0783 | 5 | Performance | Graceful degradation; unavailable is reported, not zero | DONE | 12 |
| VYB-0784 | 5 | Performance | Observability: metrics, health, integration status, request tracing | DONE | 12,16 |
| VYB-0785 | 5 | Performance | Backup and restore rehearsed and documented | DONE | 15 |

## Session 1 — Phase 0 Foundation

**Completed:** VYB-0001 … VYB-0016

**Not done:** nothing in Phase 0 scope.

**Discovered:**
- Testcontainers' default user owns the schema, so RLS does not apply to it. The
  fixture must create a separate non-owner `vyoog_app` role or the isolation tests
  pass over a leaking system. Handled in `PostgresFixture`.
- `outbox_event` must be excluded from RLS: the relay reads across tenants by design.
  It is protected by role grants instead.

## Session 2 — Tenancy model rewrite (docs/DECISIONS.md D3, D6)

**Context:** D3 was marked provisional in session 1, pending review of `vyg-pms`.
That review happened this session (see D3's full rationale) — the company's real
infrastructure is schema-per-app on shared Postgres, not tenant_id+RLS. Vyoog's own
schema (`vyg_requirement`) already exists on that shared instance. Rewrote Phase 0's
tenancy layer to match rather than build a second, inconsistent isolation model.

**Superseded (no longer implemented, and not the pattern to follow going forward):**
VYB-0002, VYB-0003, VYB-0004, VYB-0005, VYB-0006, VYB-0008, VYB-0012 — all RLS/
`tenant_id`-specific. The classes that implemented them (`TenantOwned`, `TenantContext`,
`TenantAwareDataSource`, `TenantDataSourceConfig`, `TenantIdentifierResolver`,
`TenantResolutionFilter`) are deleted. `TenantIsolationIT` is replaced by
`FoundationSmokeIT`, which proves the app starts against the real schema shape and a
product round-trips — without the cross-tenant half, which no longer applies.

**Also done this session:**
- VYB-0010 redone against the new schema-per-app model (Flyway targets
  `vyg_requirement`, owned by `postgres`, no separate migrator role).
- D6 recorded: identity moves from a Vyoog-local Keycloak realm to the shared `eVyoog`
  realm (`https://user.evyoog.com`), matching vyg-pms and the pricing tool. Local
  Keycloak/keycloak-db removed from `docker-compose.yml`; `infra/keycloak/` removed.

**Discovered / flagged, not yet acted on:**
- Phase 5.4 "Tenant lifecycle" (VYB-0730–0734 in the canonical register) assumes
  multiple tenants exist to provision/suspend/export/delete. Not applicable to a
  single-tenant deployment — flag to whoever maintains the canonical requirement
  register rather than silently building it anyway.
- This register's session-1 IDs (VYB-0001…0016) do not line up one-to-one with the
  canonical `phase-0-foundation.md` numbering the register's own README.md promises
  (e.g. this file's VYB-0002 "SET LOCAL" vs. the canonical doc's VYB-0012). Worth a
  reconciliation pass before Phase 1 starts using canonical IDs in commit trailers,
  so `Requirement: VYB-nnnn` actually points at a real row in the shared register.
- The eVyoog realm has no audience mapper for a `vyoog-api` client yet, and no public
  PKCE client for this app's frontend (`vyoog-web` by default). Both need creating in
  Keycloak by an admin before sign-in works end to end against the real realm — see
  `docs/vyoog-getting-started.md`.

## Session 3 — Phase 1 register core (backend)

**Reconciled the IDs.** Session 1's placeholder rows (old VYB-0101…0106, invented
numbers that didn't match the canonical `phase-1-register.md`) are replaced above with
the real canonical IDs, per the reconciliation flagged at the end of session 2.

**Completed:** VYB-0100, 0110–0116, 0118, 0120, 0125, 0133, and a working slice of
0101/0117/0130.

**Scope for this session, deliberately:** the requirement aggregate end to end —
immutable revisions, the state machine, acceptance criteria, optimistic concurrency, and
the audit trail — plus just enough Portfolio (Application, Capability) to place a
requirement somewhere real. Everything to the right of that (trace graph, the six
detectors, all of the frontend) is untouched. Phase 1's own exit criterion ("author a
requirement, link it upstream, and watch real gaps appear") needs the trace graph and
detectors too — this session is a load-bearing slice of it, not the whole thing.

**New this session:**
- `V002__requirement_core.sql` — forward-only migration (V001 already shipped; edits
  now go in new files, not into the baseline). Adds `archived_at` to the three
  portfolio tables and `requirement_key_seq`.
- Domain: `Application`, `Capability`, `RequirementRevision`, `AcceptanceCriterion`,
  `AuditEvent` entities + repositories; `RequirementService`,
  `AcceptanceCriterionService`, `RequirementKeyAllocator`, `AuditService`.
- API: `RequirementController`, `AcceptanceCriterionController`, `ApplicationController`,
  `CapabilityController`; `ApiExceptionHandler` extended for 404 / 409 stale-revision
  (carries both revisions per VYB-0120 AC2) / 409 unique-constraint.
- Tests: `RequirementStatusTest` (exhaustive transition table, VYB-0115 AC3),
  `RequirementServiceTest` (Mockito, no DB — revision policy, concurrency, transition
  guards), `RequirementApiIT` (Testcontainers — key allocation, real unique
  constraints, jsonb audit round-trip). `./mvnw -B -o test` green (23/23); the two `*IT`
  classes need Docker to actually run, which this sandbox doesn't have — run
  `./mvnw -B verify` on a machine with Docker before trusting them.

**Not done, flagged rather than silently skipped:**
- **VYB-0101** (update): Product/Application/Capability have create/read/archive but no
  rename/edit endpoint yet.
- **VYB-0102/0103** (glossary): the baseline schema's `glossary_term` table has no
  application linkage at all — it's a flat, globally-unique term list. VYB-0103's
  "flag a term defined differently in two applications" needs a schema change (a
  per-application definition, or an explicit usage table) before it can be built as
  specified. Flagging the schema gap rather than guessing at a design.
- **VYB-0117** (verification predicate): the `requirement_verification_state` view
  already exists (V001) and reads correctly once data exists, but there is no write
  path yet — that's Phase 2's test-ingestion work (VYB-0310+). The *demotion* half
  (VYB-0118, revision bump drops VERIFIED to APPROVED) is done now since it's pure
  domain logic in `Requirement.applyRevision`.
- **VYB-0119** (lifecycle history): no dedicated table in the baseline. Likely derives
  from `requirement_revision` + Phase 2/3 tables (`review_participant`, `verification`,
  `deployment_requirement`) rather than needing its own — worth designing once those
  exist rather than adding a redundant table now.
- **VYB-0121/0122** (bulk edit), **0123** (attachments), **0124** (comments),
  **0132** (idempotency key on create) — straightforward, just not in this session's
  slice.
- **VYB-0130/0131**: list endpoint supports one filter at a time (status *or*
  capability) and basic paging, not AND-composed multi-field filtering or server-side
  sort/group — the grid contract is real frontend+backend work for its own session.

## Session 4 — Trace graph (backend)

Also folded in the Keycloak client decision from between sessions 3 and 4: D7 in
`docs/DECISIONS.md` records reviewing `vyg-pms` for a reusable OIDC client and finding
none suitable (confidential client + password grant + secret shipped in the frontend
bundle) — `vyoog-web` is a new, separate, PKCE-only public client instead, targeting
`https://requirements.evyoog.com`. `SecurityConfig`'s CORS origins and
`frontend/.env.production` now point at that hostname. Still not registered in Keycloak.

**Completed:** VYB-0140, 0142–0146, and a scoped slice of 0141.

**New this session, all in a new `com.vyoog.trace` package:**
- `TraceLink` entity + repository; `TraceObjectType`/`TraceLinkType` enums mirroring
  the DB CHECK constraints.
- `TraceGraphService` — link create/delete/review, closure maintenance, bounded
  traversal, coverage. Per CLAUDE.md's style rule ("recursive CTEs go in jOOQ or
  JdbcTemplate, never JPQL"), everything recursive here is raw SQL via `JdbcTemplate`,
  not Hibernate.
- **Closure maintenance (VYB-0142/0143):** rather than incrementally patching
  `trace_closure` row by row on every link change — which needs a justification-count
  per row to know when a row is safe to remove, and is easy to get subtly wrong — every
  link write does a full transactional recompute from `trace_link`. `docs/DECISIONS.md`
  D5 already called the graph "small and always tenant-scoped"; a full recompute at
  that scale is simpler and provably correct, and it makes "rebuild" and "the thing
  that runs on every write" the same function by construction, so they can't drift
  apart from each other. `checkClosureDrift()` is the separate read-only comparison
  for catching *external* drift (a migration or manual SQL that bypassed the service).
- **Cyclic-graph safety (VYB-0144):** every recursive query carries a `visited` array
  of already-seen nodes on the current path, *and* a hard depth cap of 12 — either one
  alone would be enough to terminate, both together make it robust to how the guard is
  used. `TraceGraphIT.cycleTerminates` builds an actual 3-node cycle and asserts on the
  result rather than just trusting it doesn't hang.
- **Path-carrying traversal (VYB-0145):** the closure table only has `(ancestor,
  descendant, depth)` — no intermediate hops — so it's used for the coverage
  projection's yes/no questions, but `upstream`/`downstream` run their own live
  recursive query that accumulates the path as a Postgres array, parsed back into
  `TraceHop` records.
- **Coverage (VYB-0146):** no new logic needed — `requirement_coverage` (a SQL view)
  already existed in V001. `TraceGraphService.coverageFor(ids)` batches it for a whole
  page in one query, and `RequirementController.list()`/`get()`/`update()`/
  `transition()` now return it inline, satisfying AC2 ("available in the grid list
  without an extra call") directly rather than adding a second endpoint the frontend
  would have to call per row.
- API: `TraceLinkController` (create/delete/review link, upstream/downstream).
- Tests: `TraceGraphIT` — duplicate rejection, endpoint-existence validation, closure
  transitivity, closure pruning on delete, upstream/downstream symmetry, path
  correctness, the cyclic-graph case, drift-freedom after normal writes, and coverage.
  All DB-backed (Testcontainers) — same caveat as session 3's ITs, needs Docker to
  actually run. `./mvnw -B -o test` still 23/23 (unit tests unaffected).

**Not done, flagged rather than silently skipped:**
- **VYB-0141** (reviewed-at-revision): only implemented for a REQUIREMENT-sourced
  link. Other `from` types (`NEED`, `DESIGN_NODE`, `CODE`, ...) have no revision
  concept in this schema at all yet, so "record the revision it was reviewed at" has
  nothing to record for them. `reviewLink` throws a clear error for those rather than
  silently no-op'ing.
- **Endpoint existence validation (VYB-0140 AC2) is real but partial** — see
  `TraceObjectType.backingTable()`: `REQUIREMENT`, `DESIGN_NODE`, `TEST`, `RELEASE`
  have a table to check against; `NEED`, `CODE`, `CLAUSE` don't exist as rows anywhere
  in the baseline schema (they're conceptual/external references — a business need, an
  inferred code change, a compliance clause), so validation is skipped for those, not
  faked as passing.
- **VYB-0147** (coverage matrix): genuinely blocked, not deferred by choice — it's a
  requirement × test grid keyed on `verification`, and nothing writes to that table
  until Phase 2's test-ingestion endpoint (VYB-0311+) exists. Building it now would be
  building a view over permanently-empty data.

## Session 5 — The six no-AI detectors (backend)

**Correctness fix carried over from session 4:** `TraceGraphService.createLink` didn't
set `reviewedAtRevision` at creation — VYB-0141 AC1 explicitly wants creation treated
as an implicit first review, not left null until someone calls the review endpoint.
Fixed before building the suspect-link detector on top of it, since that detector's
correctness depends on it. `reviewLink` also no longer trusts a caller-supplied
revision number — it looks up the upstream's actual current revision itself, closing a
small hole where a stale client could rubber-stamp a review against the wrong number.
VYB-0141 moved from PARTIAL to DONE.

**Completed:** VYB-0134, 0150–0159, 0162, 0163, and scoped slices of 0160/0164.

**New this session, in a new `com.vyoog.detection` package:**
- `Detector` interface + `Candidate` record (VYB-0150) — a detector only reads and
  returns candidates; `FindingReconciler` is the only thing that ever writes to
  `finding`.
- `FindingReconciler` (VYB-0151/0152/0154) — reconciles a rule's fresh candidate set
  against what already exists, by fingerprint (`rule|objectType|objectId|
  discriminator`, deterministic, no hashing needed since nothing about it is
  random or session-dependent). OPEN/ACCEPTED findings refresh in place; a candidate
  matching a RESOLVED finding reopens it; anything not seen this run resolves.
  DISMISSED is the one state that's conditional: it only reopens if
  `finding.object_revision` (new column, `V003__detection.sql`) differs from the
  candidate's — otherwise it's left completely untouched, content included, because a
  human already looked at it.
- The six detectors (`noverify`, `orphan`, `nodesign`, `noac`, `suspect`, `ambig`),
  each one JdbcTemplate query. Three of them (`noverify`, `orphan`, `nodesign`) reuse
  the `requirement_verification_state`/`requirement_coverage` views from V001 rather
  than reimplementing "is it verified" / "does it have upstream" as a second
  definition that could drift from the view's.
- `AmbiguousTermLexicon` — the wording detector's (and the lint endpoint's) shared
  term list. See the gap noted below.
- `FindingService` (dismiss/accept) and `DetectionSweepService` (orchestration,
  concurrency guard, rate limit, nightly `@Scheduled` trigger).
- API: `FindingController` (list/dismiss/accept/manual-sweep), `LintController`
  (VYB-0134 — same lexicon, no persistence).
- Tests: `AmbiguousTermLexiconTest`, `FindingReconcilerTest` (Mockito, no DB — every
  reconcile-state transition), `DetectionIT` (Testcontainers — all six detectors'
  flag/resolve behaviour, the dismissal-survives-a-rerun and
  dismissal-reopens-after-revision-bump cases, disabled-rule-produces-nothing, and the
  sweep rate limit). `./mvnw -B -o test` green (36/36); the DB-backed IT still needs
  Docker to actually run, same caveat as every session since 3.
- One test-design bug caught before it shipped: the rate-limit test originally called
  the shared `DetectionSweepService` singleton bean directly — its cooldown state is
  in-memory and would leak across test methods with no guaranteed ordering, so any
  test could get spuriously rate-limited by an unrelated one that happened to run
  first. Fixed by constructing a fresh, throwaway instance per test that needs to
  control that timing precisely.

**Not done, flagged rather than silently skipped:**
- **VYB-0160 lexicon provenance:** AC1 asks for "at least the 45 terms from the
  validated prototype." That exact list isn't available this session —
  `AmbiguousTermLexicon` has 59 terms built to the same intent (standard
  weak-requirements-language guidance), not a verified match to the original. Worth
  reconciling if the real prototype list turns up.
- **VYB-0161 (bounded, triggered-on-change detection):** every sweep here is a full
  scan, not a bounded re-evaluation of just the changed object and its neighbours.
  That needs a hook into every write path (or the outbox), which doesn't exist yet.
  Deliberately using "correct but unscoped" as the interim rather than half-building
  the event wiring — the six detectors are cheap enough that a full sweep costs little
  at today's data volume.
- **VYB-0164 (per-rule tuning) is partial:** enable/disable works (`GapRule.enabled`,
  checked by `DetectionSweepService`); the `threshold`/`config` columns exist on
  `gap_rule` but nothing reads them yet — none of today's six detectors have a
  tunable threshold to apply one to.
- **Where this code lives is provisional:** `DetectionSweepService`'s `@Scheduled`
  nightly trigger runs inside `vyoog-api` because `vyoog-worker` has no Spring Boot
  application of its own yet. Flagged in the class doc — move it once that module is
  actually stood up as its own deployable process, per the original module layout in
  README.md.

## Session 6 — Frontend: requirements, portfolio, findings

**Verified against real tooling, not just typed by hand:** `node_modules` already
existed in this environment, so every screen below was checked with
`npx tsc -b --noEmit` (strict mode, `noUnusedLocals`/`noUnusedParameters` on) and a
full `npm run build` after each major addition — both clean. `npm run lint` doesn't
work here (`eslint` isn't actually installed despite being in `package.json`, unrelated
to this session) and `npm run test` has no test files to run — see the gap noted below.

**One small, justified backend addition first:** the detail panel needs to show and
manage a requirement's *existing* trace links, and no endpoint returned that — only
`upstream`/`downstream` traversal existed, which returns reachable nodes and paths, not
the link rows themselves (no link id, so nothing to delete or review against). Added
`TraceLinkRepository.findAllByFromType.../findAllByToType...`, a
`TraceGraphService.linksFor(type, id)` passthrough, and `GET /trace/{type}/{id}/links`.
Mechanical, no new business logic, same patterns as everything else in that class.

**New this session:**
- `shared/api/client.ts` — full typed surface for every backend endpoint that exists
  as of session 5: requirements (list/get/create/update/transition), acceptance
  criteria (list/add/reorder/remove), portfolio (products/applications/capabilities,
  create + archive), trace (create/delete/review link, list direct links,
  upstream/downstream), findings (list/accept/dismiss/sweep), lint. `ApiError` now
  carries the RFC 9457 response's extra properties (e.g. `attemptedRevision`/
  `currentRevision` on a 409), not just title/detail.
- `shared/ui/Badges.tsx` — `StatusBadge`, `PriorityBadge`, `SeverityBadge`,
  `CoveragePips` (VYB-0181, letter-coded so colour is never the only signal per
  VYB-0770).
- CSS additions to `tokens.css`: table, badge/pip, form control, modal, list-item
  utility classes — same token-based, no-component-library convention as Phase 0's
  original styles, extended rather than replaced.
- `Requirements.tsx` rebuilt: a real, working paginated table against
  `GET /requirements` (status filter, coverage pips, click-through to detail) — not
  the full grid contract, which needs backend work (multi-field filters, sort) this
  session didn't do either.
- `RequirementNew.tsx` — cascading product→app→capability placement (VYB-0200), live
  lint on the statement with a 300ms debounce calling the real `/lint` endpoint
  (VYB-0201).
- `RequirementDetail.tsx` — the biggest addition: attributes/statement editing with a
  local draft (VYB-0190), acceptance criteria add/reorder/remove, lifecycle transition
  buttons derived from the same table `RequirementStatus.allowedNext()` encodes
  server-side (VERIFIED deliberately has no button — the API refuses it as a
  human-initiated target and there's no point offering one that always fails),
  optimistic-concurrency conflict handling (catches the 409, shows both revisions,
  offers reload), and trace link management (list, add by pasting the other
  requirement's id, delete, mark reviewed).
- `Portfolio.tsx` rebuilt: three-column cascading view (products → applications →
  capabilities), create + archive at every level, not just products.
- `Analytics.tsx` rebuilt: findings list filterable by state, accept/dismiss
  (dismissal prompts for a reason and refuses to submit without one, mirroring the
  API's own refusal), a "Run sweep" button showing the real per-rule counts back.
- `Home.tsx` rewired to real counts: total requirements, open findings, verified
  percentage — all from live queries, matching VYB-0230's "derived, never stored" rule
  literally (there's nowhere in this codebase a dashboard number could be cached
  stale, because nothing caches one).

**Not done, flagged rather than silently skipped** (see the table above for the
specific IDs — summarised here, not repeated):
- **The grid is a working table, not the VYB-0170–0180 grid contract.** No
  virtualisation, column drag/resize/pin, multi-column sort, per-column filter types,
  grouping, density modes, row selection, or inline cell edit. Building the real
  contract needs backend work this session didn't do either (AND-composed filters,
  server-side sort) — doing the frontend half alone would be building UI for an API
  that can't back it yet.
- **No bulk edit, no undo toast, no "confirm" dialog pattern (VYB-0180/0182/0183).**
  Destructive/consequential actions here use a bare `window.prompt`, not the named-
  count-and-objects confirm dialog the spec asks for. Functional, not the specified UX.
- **Acceptance criteria can be added/reordered/removed but not edited in place** — to
  fix a typo you remove and re-add. `AcceptanceCriterion` has no PATCH endpoint on the
  backend either.
- **Traceability display (VYB-0192) is direct links only** — doesn't use the
  upstream/downstream traversal endpoints or show a full chain, and doesn't yet render
  the specific "stops short" language the spec asks for.
- **No lifecycle timeline (VYB-0193).** There's genuinely nothing to render it from —
  session 3 flagged VYB-0119 (lifecycle history) as blocked on Phase 2/3 tables that
  don't exist yet.
- **Concurrent-edit handling (VYB-0194) detects and reports the conflict but doesn't
  offer keep-mine/keep-theirs/merge** — just "reload and redo your edit." Real, not
  silent data loss (the whole point of VYB-0194), just not the full three-way choice.
- **The authoring screen has one save path** (save as draft) of the three VYB-0205
  asks for (no add-another, no direct submit-for-review).
- **No frontend automated tests.** `vitest` is configured (from Phase 0) but no
  `*.test.tsx` files exist for any of this session's screens, and no
  `@testing-library/react`-equivalent is installed to write them with. Real gap, not
  papered over by the clean `tsc`/`vite build` — those prove the types and the bundle,
  not the behaviour.
- **Documents/matrix/graph views (1.9), the rules-tuning and lifecycle-spine screens
  (parts of 1.10), and the release/flow parts of Home (1.11)** are entirely untouched.

## Session 7 — Rules screen, suspect links, reopen, requirement flow

**Two small backend additions, both mechanical, both test-verified (36/36 still
green):**
- `Finding.reopenManually(actor)` / `FindingService.reopen` / `POST
  /findings/{id}/reopen` (VYB-0224 AC2) — distinct from the reconciler's own
  package-private `reopen(Candidate)`, which is a different actor (the sweep, not a
  human) doing a different thing (noticing a candidate recur, not overriding a
  dismissal). `dismissReason` is left as history rather than cleared on reopen — it
  stays true that someone dismissed it once, for that reason.
- `GapRuleService` — extracted the "a rule with no config row yet is enabled by
  default" business rule out of `DetectionSweepService` (where it was inline) into one
  place, because the new `RuleController` needed the same answer and duplicating a
  business rule across two call sites is how they drift. `DetectionSweepService`'s
  constructor now takes `GapRuleService` instead of `GapRuleRepository` directly —
  `DetectionIT`'s `freshSweepService()` helper and its one direct `GapRule` save
  updated to match, now going through the service like production code does.
- `RuleController` — `GET /rules` (every template + its live enabled state), `PATCH
  /rules/{key}` (toggle). VYB-0223 AC2 ("the rate is computed from real dismissals")
  is explicitly not built — no query anywhere computes a per-rule dismissal rate.

**Frontend, verified the same way as session 6** (`npx tsc -b --noEmit` and
`npm run build`, both clean; `npm run lint`/`npm run test` still unavailable for the
same pre-existing reasons noted in session 6):
- `Analytics.tsx` restructured into three tabs — Findings (unchanged, plus a Reopen
  button on DISMISSED rows), **Suspect links** (VYB-0214: findings filtered to
  `ruleKey=suspect`, each with a "Mark reviewed" button calling `reviewLink` directly
  against the finding's `objectId` — which *is* the trace link's id, by
  `SuspectLinkDetector`'s own design choice from session 5), and **Rules** (VYB-0223:
  lists every `gap_rule_template` with its live enabled state and a disable/enable
  button).
- `Home.tsx` gained a requirement-flow strip (VYB-0232): counts across
  DRAFT→IN_REVIEW→APPROVED→VERIFIED, with the largest drop between consecutive stages
  marked "Stuck here" (AC2 — the *largest* drop, not just the smallest count, which
  would have marked VERIFIED stuck on every healthy register simply for being the
  last, smallest bucket).
- One implementation note worth keeping: the first draft of the flow strip called
  `useQuery` inside `FLOW_STAGES.map(...)` — four hook calls, so it never actually
  broke anything since the array length is fixed, but it's the shape of a real
  rules-of-hooks violation and the linter would rightly flag it. Replaced with
  `useQueries`, the actual supported pattern for a dynamic list of parallel queries.

**Not done, flagged rather than silently skipped:**
- **The suspect-links tab has an honest gap in what "Mark reviewed" actually does
  immediately.** Reviewing a link clears its own staleness in the trace graph right
  away, but the *finding* that names it stays OPEN until the next sweep notices the
  candidate no longer recurs and resolves it — there's no auto-sweep-after-review
  wired up (the 30-second rate limit makes an automatic trigger awkward), so the UI
  says as much rather than implying instant resolution it doesn't deliver.
- **VYB-0222 (lifecycle coverage spine)** still isn't built — it wants seven
  stages (author→review→approve→develop→verify→deploy) with a break at each junction
  linking through to Analytics; most of those stages have no real data behind them yet
  (session 3's VYB-0119 note still applies). VYB-0232's four-stage flow strip is a
  deliberately smaller, honest stand-in using only statuses that actually exist.
- **VYB-0231/0233 (blocking-this-release, not-owned-here)** need a release concept
  (Phase 3) and integration-connection data (nothing populates `integration_connection`
  anywhere yet) respectively — both genuinely blocked, not deferred by choice.
- **Documents/matrix/export/trace-graph-visualisation (the rest of 1.9)** remain
  untouched — matrix is blocked on Phase 2 verification data same as VYB-0147, and
  documents need a backend domain (the `document`/`import_batch` tables exist in the
  baseline but have no entities, repos, or endpoints yet).

## Session 8 — Closing out Phase 1: portfolio edit, glossary, bulk edit, comments, attachments, similarity, idempotency, bounded detection

**Backend, all test-verified — 36/36 unit + ArchUnit tests still green, `mvn compile`
clean across the reactor. No new `*IT.java` assertions were actually executed (still
no Docker in this sandbox); new integration coverage was added to `Session8IT.java`
and confirmed to *compile*, not to pass against a real database.**

- **VYB-0101** — `ProductController`/`ApplicationController`/`CapabilityController`
  each gained a `PATCH /{id}` (`UpdateProduct`/`UpdateApplication`/`UpdateCapability`
  records) alongside the existing create/list/archive. Closes the gap session 3 and 7
  both flagged.
- **VYB-0102/0103** — `GlossaryTerm`/`GlossaryTermUsage` entities, `GlossaryService`,
  `GlossaryController` (`/api/v1/glossary`, `/glossary/{id}/usage/{applicationId}`,
  `/glossary/conflicts`). A conflict is: two or more applications record a definition
  for the same term, and those definitions disagree (an app that leaves it unset
  inherits the canonical definition and is never counted as a variant on its own).
- **VYB-0121/0122** — `Requirement.reassign(...)` (metadata-only — priority, type,
  capability — no revision bump, since these aren't the content the revision
  contract protects) plus `BulkEditService.apply`/`undo` and `POST
  /requirements/bulk-edit` / `.../bulk-edit/{batchId}/undo`. Per-row: an illegal
  status transition on one row is caught and skipped, the rest still apply. Undo
  replays the audit event's before-snapshot per row.
- **VYB-0123** — `Attachment`/`AttachmentVersion` entities, S3-compatible
  `AttachmentService` (AWS SDK v2, MinIO-compatible endpoint via
  `vyoog.storage.*` config), `AttachmentController`. **Genuinely untested** — no
  MinIO/S3 reachable from this sandbox, so upload/download has only been read for
  correctness, never executed. PARTIAL, not DONE, until someone runs it against a
  real object store.
- **VYB-0124** — `RequirementComment`, `CommentService` (extracts `@mention`s by
  email-local-part or display name; an unresolvable mention is dropped silently
  rather than failing the whole comment), `CommentController`.
- **VYB-0130** — `RequirementSpecifications` (AND-composed `Specification`s for
  status/capability/type/priority/owner/title) plus native multi-field sort via
  `Pageable`. `ApiExceptionHandler` now turns an unknown sort field or a
  type-mismatched filter value into a 400 naming the field, not a 500.
- **VYB-0132** — `idempotency_key` table + `IdempotencyService`; `POST
  /requirements` accepts an `Idempotency-Key` header and replays the first response
  on a repeat.
- **VYB-0135** — `RequirementSimilarityService` (pg_trgm `similarity()` via
  `JdbcTemplate` — not expressible in JPQL), `GET /requirements/similar?statement=`.
- **VYB-0161** — every `Detector` gained `scanOne(UUID)` — a real bounded query, not
  the full scan filtered client-side. `FindingReconciler` split into
  `reconcileWithin`/`reconcileOne` so a bounded rescan only resolves findings scoped
  to *that* object — the first design resolved every open finding for the rule
  globally, since they weren't in the tiny single-object candidate list, and got
  caught before shipping. Wired into `RequirementService`/`AcceptanceCriterionService`/
  `TraceGraphService`'s mutating methods, each calling `.flush()` first — a raw JDBC
  query on the same connection right after a Hibernate write doesn't see the pending
  change otherwise, also caught before shipping rather than discovered as a flaky test.

**Frontend, verified via `npx tsc -b --noEmit` and `npm run build` (both clean) —
same caveat as sessions 6/7: no component tests exist, and no test runner is
installed to write them with.**

- **Grid (1.10-ish slice of VYB-0170–0180)**: `Requirements.tsx` gained type/priority
  filter dropdowns, clickable sortable column headers (key/title/status/priority),
  row selection with a header select-all, and a "Bulk edit" action that opens
  `BulkEditModal.tsx` and, on apply, an `UndoToast.tsx` (VYB-0182, ≥7s window, a
  failed undo says so in place rather than vanishing). Still no virtualisation,
  column reorder/resize/pin, grouping, density control, or inline cell edit — PARTIAL,
  not DONE, on the combined row.
- **VYB-0195**: `RequirementDetail.tsx` gained Comments (list + `@mention` posting)
  and Attachments (list, upload, download-as-blob-with-bearer-token — a plain
  `<a href>` can't attach the auth header) sections. PARTIAL to match VYB-0123 — the
  UI is wired but nothing has exercised it against a live object store.
- **VYB-0204**: `RequirementNew.tsx` runs a debounced trigram similarity check
  alongside the existing lint debounce (only once the statement passes ~20 characters,
  so a short fragment doesn't flag as "similar" to everything by chance) and shows a
  dismissible panel of near-duplicates. Advisory only — it never blocks saving.
  VYB-0132's idempotency key is also wired here: one `crypto.randomUUID()` generated
  once per visit to the screen, so a double-click or a retried request can't create
  the requirement twice.
- **VYB-0102/0103 (frontend)**: `Portfolio.tsx` gained a Hierarchy/Glossary tab
  switcher. Glossary lists terms, creates new ones, records a per-application
  definition (gated on an application being selected in the Hierarchy tab — there's
  no second application picker, to avoid one drifting from the real hierarchy), and
  lists conflicts.
- **VYB-0101 (frontend)/VYB-0183**: `Portfolio.tsx`'s hierarchy columns gained an
  inline rename (pencil icon → text field → Enter/blur to commit, preserving the
  other fields on that row's PATCH body rather than clobbering them with `undefined`)
  and archiving now goes through `ConfirmDialog.tsx`, naming the exact item and what
  archiving it cascades to hide, instead of firing on the first click.

**Not done, flagged rather than silently skipped:**
- **VYB-0119 (lifecycle history) and VYB-0193 (lifecycle timeline)** remain TODO —
  nothing records a per-stage timestamp beyond the audit trail's own event log, and
  the timeline UI has nothing purpose-built to render.
- **VYB-0147 (coverage matrix) and VYB-0231/0233 (blocking-this-release,
  not-owned-here)** remain genuinely blocked on Phase 2/3 concepts (test execution
  results, releases, integration connections) that don't exist yet — unchanged from
  session 7's assessment.
- **Bulk edit doesn't offer owner reassignment or reassigning to a *specific* new
  capability** — only unassigning one. `BulkEditRequest.ownerId`/`capabilityId` exist
  on the wire contract for later, but no picker exists at this scope yet.
- **VYB-0206 (confirmation is targeted, not routine)** is PARTIAL, not DONE:
  `ConfirmDialog` is wired for Portfolio archiving (the clearest cascading, hard-to-
  reverse action) but not yet for removing an acceptance criterion, deleting a trace
  link, or dismissing a finding — those still fire on first click.
- **VYB-0131 (server-side grid contract)** is PARTIAL: filter/sort/page all run
  server-side now, but nothing has been measured at anything near 50k rows, and
  there's no client-side virtualisation to keep a large page's DOM cheap.
- **The eVyoog Keycloak client secret and local Postgres/MinIO access remain
  unresolved** (per the last several sessions) — this session's backend work is
  compiled and unit-tested only; the `*IT.java` suite has still never run against a
  real database in this environment.

## Session 9 — Phase 2: flow and evidence

**The baseline schema (V001, written up front against the full spec) already had
almost every table Phase 2 needed** — `review`/`review_item`/`review_participant`,
`test_case`/`test_run`/`verification`, `defect`, `clarification`, `change_request`,
`outbox_event`, `notification`, `access_grant`, `service_account` all existed before
this session touched anything. `V005__phase2_flow_and_evidence.sql` fills the
handful of real gaps: a `review_comment` table (round-level comments had nowhere to
live), an append-only trigger on `review_participant` signatures (mirroring
`audit_event`'s), `defect_key_seq`/`change_request_key_seq`, `ingested_commit` (a
commit's identity is a SHA, not a UUID — this is the mapping the trace graph's CODE
type needs), a `sod` gap-rule-template row, and `change_request.rationale` +
`change_request_requirement` (the baseline's change_request had impact *counts* but
nowhere to record its actual scope or why it was raised).

**Backend — new packages, all test-verified (47/47 unit + ArchUnit green, up from 36;
11 new tests target the trickiest invariants — separation of duties, the
approved-requires-a-change-request gate, only-the-assignee-or-delegate-may-answer).
`*IT.java` integration tests still only compile, same disclosed limitation as every
prior session — no Docker/Postgres in this sandbox.**

- **`com.vyoog.identity`** — `AccessGrant`/`ServiceAccount` entities, `GrantService`
  (does a grant cover a capability, walking capability→application→product — three
  levels, not a recursive query, because the hierarchy is exactly three levels by
  design), `ServiceAccountChecker` and `StepUpChecker` — both pure logic taking plain
  claim strings rather than a `Jwt`, so the domain layer still never imports a
  Spring-Security type; `PrincipalGuard` in `vyoog-api` is where the raw JWT and that
  pure logic actually meet.
- **VYB-0300–0307 (`com.vyoog.review`)** — `Review`, `ReviewComment` as JPA entities;
  `review_item`/`review_participant` read and written as raw SQL in `ReviewService`,
  same choice already made for `trace_closure` — neither has a surrogate key or needs
  loading as an object graph. `ReviewService.open` auto-enrolls every approver whose
  grant covers the round's requirements (VYB-0343 needs someone to actually be a
  participant, not just theoretically eligible, or there'd be nothing for them to
  sign). Separation of duties (VYB-0304) is checked before a signature is ever
  recorded; `SeparationOfDutiesDetector` (new gap rule `sod`) is the safety net for
  whatever predates that check or slips past it (an owner reassigned after signing).
- **VYB-0310–0316 (`com.vyoog.evidence`)** — `TestCase`/`TestRun`/`Verification`,
  `VerificationService` (idempotent per run, auto-creates an unknown test key rather
  than dropping it), `IngestedCommit`/`CommitIngestService` (parses `Requirement:
  KEY` trailers — deliberately matching this system's own generated key format,
  `VY-nnnn`, not the spec's own `VYB-nnnn` numbering for *its* 323 requirements; those
  are two unrelated numbering schemes and conflating them would have created links to
  keys that don't exist), `UntracedCommitDetector`. `RequirementService` gained
  `promoteToVerifiedSystemically` — the only code path that can ever set VERIFIED,
  called exclusively from evidence ingestion; `transition()` already refused a human
  target of VERIFIED since session 3, so this is additive, not a behaviour change.
- **VYB-0320–0323 (`com.vyoog.defect`)** — `Defect`, `DefectService`. Routing
  (VYB-0322) only has a `developer_id` *column* to write to — the baseline schema has
  no `tester_id` on `defect` at all — so the tester side of routing is a notification
  only, disclosed rather than worked around with a column that isn't there.
- **VYB-0330–0334 (`com.vyoog.clarification`)** — `Clarification`, `ClarificationService`
  (raise/answer/escalate). `AppUser` gained `delegateId` (the column already existed
  in the baseline; nothing had mapped it yet) so "only the assignee or their
  delegate may answer" (VYB-0381 AC1) is real, not just a frontend suggestion.
  Escalation (VYB-0334) is a `@Scheduled` job on the service itself, same rationale as
  `DetectionSweepService`'s nightly sweep — `vyoog-worker` still has no Spring Boot
  application of its own.
- **VYB-0340–0349 (`com.vyoog.tasks.TaskService`)** — no entity, no table (VYB-0340
  AC2), seven methods each a direct SQL query for one derivation condition, run fresh
  on every call. "Since"/reason text uses `updated_at` as a stand-in for "entered this
  stage" — an approximation disclosed in the class's own Javadoc, not just here.
- **VYB-0355–0356 (`com.vyoog.notify`)** — `Notification`, `NotificationService`,
  called directly from the services above in the same transaction as the event
  itself. `outbox_event` remains unused — see the gap list.
- **VYB-0390–0393 (`com.vyoog.changerequest`)** — `ChangeRequest`,
  `ChangeRequestService`. Impact (VYB-0391) reuses `TraceGraphService.downstream`
  (exact graph queries, per AC2) plus two joins for applications/briefs, computed
  fresh on every call, never cached past the moment it might be stale.
  **VYB-0390 AC1 is a real behaviour change to Phase 1 code**: `RequirementService
  .update()` now refuses editing an APPROVED/VERIFIED requirement outright unless a
  `changeRequestId` names an APPROVED change request that scopes it — checked by
  `ChangeRequestController`/`ChangeRequestService.assertCanEdit` before
  `applyChangeRequestEdit` (the one bypass) ever runs. VYB-0392 ("mark downstream
  suspect on acceptance") turned out to need no bespoke code at all: the edit itself
  bumps the revision exactly like any other edit, and the bounded rescan that already
  runs (VYB-0161, session 8) already re-evaluates `SuspectLinkDetector` on that id —
  the existing mechanism was already sufficient once the edit path existed.

**Frontend, verified via `npx tsc -b --noEmit` and `npm run build` (both clean):**

- **`Quality.tsx`** (previously a Phase-0 placeholder) — three tabs. Review rounds:
  bucketed active/awaiting-my-signature/closed (VYB-0360), open-a-round form,
  sign/close with the errors named. Verification: the three figures (VYB-0362 AC2 —
  unverified and stale shown as separate lists, never conflated) plus both lists.
  Defects: the requirement-vs-coding-error split as an actual percentage (VYB-0364
  AC2), raise/classify/close.
- **`MyWork.tsx`** (same) — Today (VYB-0370, bucketed by how long the underlying
  condition has been true, disclosed as an approximation since tasks carry no due
  date of their own), Pipeline (VYB-0371, four lanes, stalled cards marked from
  `TaskService.stalled()`), Perspective (VYB-0373, an id field gated server-side by
  the ADMINISTRATOR grant). Calendar (VYB-0372) is not built — `release` has no
  milestone dates anywhere in the schema, so there is nothing real to plot without
  inventing dates that don't exist.
- **`RequirementDetail.tsx`** — a Clarifications section (raise, answer with an
  optional "this changes the meaning" checkbox that raises a change request and
  links it back), a locked-for-editing banner once APPROVED/VERIFIED naming why and
  taking a change-request id, and a "Raise defect" action pre-linked to the
  requirement (VYB-0365).
- **`Analytics.tsx`** gained a fourth tab, Change requests (VYB-0393) — raise, compute
  impact (shown before the approve button is even enabled — VYB-0391/0393 AC1),
  approve/reject, mark applied.

**Not done, flagged rather than silently skipped:**
- **VYB-0303/0305 (step-up auth, service-account detection) are PARTIAL, not DONE**:
  both are real, working code paths, but neither has ever been exercised against a
  real Keycloak token. Step-up compares the JWT's `acr` claim against a configured
  value — this realm has no conditional-ACR flow configured, so it's never seen an
  elevated token. Service-account detection falls back to "no `email` claim" when the
  caller's `azp` isn't a registered `service_account.client_id` — never checked
  against an actual client-credentials token from eVyoog.
- **VYB-0322 (defect routing) is PARTIAL**: the tester side is notification-only,
  since `defect` has no `tester_id` column in the baseline schema.
- **VYB-0334 (escalation) is PARTIAL**: escalates to the capability owner only —
  there's no manager relationship anywhere in `app_user`, so "the assignee's manager"
  literally can't be resolved; falls back to whoever raised it if there's no
  capability owner either.
- **VYB-0356/0357 (outbox-based, digested notifications)**: notifications write
  directly in the same transaction as their cause (the guarantee VYB-0356 actually
  cares about still holds) but never through `outbox_event`, which remains an unused
  table; VYB-0357's coalescing window isn't built at all — ten edits produce ten
  notifications.
- **VYB-0363/0372 (draft test cases, calendar) aren't built** — the former needs an
  LLM drafting path this session didn't build, the latter needs milestone dates that
  don't exist in the schema yet.
- **VYB-0373 (perspective switching) reuses the ADMINISTRATOR grant** rather than a
  purpose-built "view others' work" role the spec's nine roles don't actually name.
- **`VerificationService`/`CommitIngestService`/`ClarificationService`'s escalation
  path have no dedicated unit tests this session** — unlike `ReviewService` (via its
  detector), `DefectService`, and `ClarificationService`'s answer/delegate logic,
  which all got Mockito coverage for their trickiest branches. The ingestion paths
  were reasoned through carefully but only compile-checked, not test-asserted.
- **The eVyoog Keycloak client secret, local Postgres/MinIO access, and the `*IT.java`
  suite never running against a real database remain exactly as unresolved as every
  prior session disclosed.**

## Session 10 — Phase 3: delivery and releases

**No new migration this time** — V001's baseline already had `brief`/`brief_requirement`,
`baseline`/`baseline_item`, `variant`/`variant_applicability`, `release`/
`release_scope_item`/`scope_movement`, `environment`/`deployment`/
`deployment_requirement`, and the entire `design_flow`/`design_node`/`design_edge`/
`design_node_requirement` set, written up front against the full spec exactly like
Phase 2's tables were. This session is almost entirely new Java over rows that already
existed.

**Backend — six new packages, all test-verified (58/58 unit + ArchUnit green, up from
47; 11 new tests target `BriefContentGenerator`, the one piece of this session's logic
pure enough to test without a database — see the gap list for what that leaves
untested).**

- **`com.vyoog.brief` (VYB-0450–0457)** — `Brief` (JPA), `brief_requirement` as raw SQL
  (no surrogate key, same reasoning as every other join table in this codebase without
  one), `BriefContentGenerator` (a genuinely pure function — no clock call, no
  repository — so the same scope always produces byte-identical markdown, VYB-0450
  AC2), `BriefService`, `BriefStalenessService`. VYB-0456's "within one detection
  cycle" is synchronous: `RequirementService.applyEdit` now calls
  `BriefStalenessService.markAffectedBriefsStale` on every revision bump, the same
  layering already used for detection rescans and separation-of-duties — this is a
  small, real change to Phase 1/2 code, not new-package-only.
- **`com.vyoog.signals` (VYB-0460–0465)** — `SignalsService`, no entities (every
  figure is a live query, nothing to persist). Each of the eight signals carries the
  literal SQL template that produced it (VYB-0461) — with `?` placeholders, never the
  caller's real capability ids, so showing "the query" never means showing something
  live and re-executable with this scope's actual values baked in. The portfolio
  median (VYB-0462) computes the same eight signals for every application and takes
  the median in Java, not a hardcoded number. VYB-0463 (no combined estimate) is
  satisfied by omission — there is no ninth field anywhere, and this paragraph is
  the documentation the AC asks for.
- **`com.vyoog.baseline` (VYB-0470–0473)** — `Baseline` (JPA), `baseline_item` as raw
  SQL, `Variant`/`variant_applicability` (also raw SQL — a variant only ever gets an
  explicit `applies = true` row narrowing its scope; this never writes `applies =
  false`, since nothing here needed an exclusion-from-implicit-all case, an honest
  unexercised use of that column). `BaselineService.freeze` doesn't check step-up
  itself — `BaselineController` does, via the same `PrincipalGuard` Phase 2 built for
  `ReviewService#sign`, before `freeze` ever runs.
- **`com.vyoog.release` (VYB-0474–0476/0483/0484)** — `Release` (JPA),
  `ScopeMovement` (JPA — this one has its own id and is genuinely queried by window,
  unlike the raw-SQL join tables), `release_scope_item` as raw SQL.
  `ReleaseService.commit` enforces "one release at a time" by checking every existing
  `release_scope_item` row for that requirement before inserting, refusing rather than
  silently migrating it. `blocked()` and `releaseNotes()` both do a single query each
  against `requirement_verification_state` plus the relevant finding rows — no new
  detector, no new view.
- **`com.vyoog.deployment` (VYB-0480–0482)** — `Environment` (ordered by a real
  `ordinal` column, never by name), `Deployment`, `deployment_requirement` as raw SQL.
  VYB-0482 AC1 ("presence derives from code links, not a manual list") shaped the CI
  ingest contract directly: the payload names which commit SHAs are in this build —
  the one fact a CI system actually has, from its own git history since the last
  deploy — and `DeploymentService` derives requirement presence from those commits'
  existing `Requirement:` trailers (VYB-0316, Phase 2), rather than asking the caller
  to separately enumerate requirement ids.
- **`com.vyoog.design` (VYB-0490–0495)** — `DesignFlow`/`DesignNode`/`DesignEdge`
  (JPA — all three have real surrogate keys and needed real repositories),
  `design_node_requirement` as raw SQL. `NodeKind`'s enum constants are lowercase
  (`start`, `step`, …) on purpose — `EnumType.STRING` persists the literal constant
  name, and the DB's own CHECK constraint is lowercase, so matching it exactly avoids
  a converter for five values. Node/flow deletion needed no application-level cascade
  logic at all: V001's `ON DELETE CASCADE` on `design_edge`/`design_node_requirement`
  already does it (VYB-0495 AC1), and nothing cascades onto `requirement` itself
  (AC2) because no FK from either join table points at it in that direction.

**Frontend, verified via `npx tsc -b --noEmit` and `npm run build` (both clean):**

- **`Delivery.tsx`** (previously a placeholder) — three tabs. Briefs: cascading
  application→capability picker (VYB-0500), target selector showing the filename and
  what actually differs (VYB-0501), a required developer-id field gating generation
  (VYB-0502), an exact markdown preview with download (VYB-0503), stale badges naming
  which requirements moved (VYB-0504), and a push-to-delivery-tool confirmation
  naming what would be written (VYB-0505) — honestly: confirming it reports that
  nothing is actually connected to push to, rather than faking a success. Signals: the
  eight cards against the portfolio median, each revealing its query on demand
  (VYB-0506), no combined estimate anywhere (VYB-0463 again, from the UI side).
  Impact: a requirement-id lookup with the six exact figures and the explicit line
  "Vyoog stops at volume" (VYB-0508).
- **`Releases.tsx`** (previously a placeholder) — six tabs (scope, movement,
  baselines, variants, deployment, notes) sharing one release picker. Scope shows
  readiness and blocked-from-release reasons (VYB-0515); movement is windowed
  (VYB-0516); baselines can be frozen and diffed, with the most recently frozen one
  marked current (VYB-0517/0518); variants render as a matrix distinguishing
  applies-to-all from edition-specific (VYB-0519); deployment shows environments in
  their real order with builds and derived presence (VYB-0520); notes group verified
  items by capability and list held (unverified) ones separately, never omitted
  (VYB-0521).
- **`Design.tsx`** (previously a placeholder) — the largest single piece this
  session. A hand-rolled layered auto-layout (VYB-0531): BFS distance from the start
  node becomes the column, which is cycle-safe by construction (a visited-set BFS
  can't loop, satisfying VYB-0532 for free) and keeps same-depth branches in the same
  column (AC2) without a graphing library. Node shapes follow kind — terminals,
  diamonds, dashed for integrations (VYB-0533); colour derives from the verification
  state of each node's linked requirements, with a non-colour text label alongside it
  so colour is never the only signal (VYB-0534). The node inspector attaches/detaches
  requirements live (VYB-0535); adding a step is undoable via the same `UndoToast`
  Phase 1 built for bulk edit (VYB-0536 AC1) and warns when nothing's linked (AC2).
  Export serializes the actual rendered `<svg>` DOM node — the diagram's colours are
  hardcoded hex values, not `var(--x)` theme tokens, specifically so the exported file
  renders identically outside this application (VYB-0538 AC1), where those custom
  properties wouldn't exist.

**Not done, flagged rather than silently skipped:**
- **VYB-0464 (impact volume) is PARTIAL**: "teams" isn't modelled as an entity
  anywhere in this schema (same gap as Phase 2's clarification-escalation "manager"),
  so the teams figure counts distinct requirement owners instead — a disclosed proxy,
  not a real team count.
- **VYB-0465/0505 (signals exportable / push-confirmation) are PARTIAL**: the response
  shapes are genuinely exportable and the confirm dialog genuinely names what would be
  written, but there is no real delivery-tool connector (no Jira/ADO/etc. integration)
  behind either — confirming a push reports that nothing is connected rather than
  pretending to succeed.
- **VYB-0502 (developer selection) is PARTIAL**: a paste-the-user-id field, not a
  picker — consistent with every other "assign a person" field built so far
  (clarification assignee, review participants), but a real gap against the literal
  ask all the same.
- **VYB-0507 (borrowed signals look borrowed) is TODO**: none of the eight scope
  signals are externally sourced — they're all computed from this system's own rows —
  so there's currently no borrowed-data card to render hatched. Nothing fabricated to
  demonstrate the pattern.
- **VYB-0537 (coverage tab) is PARTIAL**: both counts render inside the Coverage tab
  itself; they are not wired onto the sidebar's module-tab badge, which would need
  the shell to fetch global data it doesn't fetch anywhere else today.
- **Step-up on baseline freeze (VYB-0471) carries the same disclosed caveat as Phase
  2's VYB-0303**: the mechanism is real, but this realm has no step-up flow
  configured, so it's never been exercised against an actual elevated token.
- **No dedicated unit tests for `SignalsService`, `BaselineService`, `ReleaseService`,
  `DeploymentService`, or `DesignService`** — all five are thin wrappers around
  `JdbcTemplate` queries, the same category of code Phase 2 already disclosed as
  compile-checked-only (`VerificationService`, `CommitIngestService`). Only
  `BriefContentGenerator`, the one pure function in this session, got real test
  coverage.
- **The eVyoog Keycloak client secret, local Postgres/MinIO access, and the
  `*IT.java` suite never running against a real database remain exactly as
  unresolved as every prior session disclosed.**

## Session 11 — Phase 4: intelligence

**No new AI provider or LLM access exists in this environment.** Rather than fabricate
model behaviour, this session built genuinely real infrastructure around that absence:
a real (if deliberately weaker-than-semantic) local embedding provider, and an LLM
adjudicator interface with zero registered implementations — so the one detector that
needs a language model honestly reports itself unavailable instead of inventing a
verdict. This is the same "AI proposes, the human decides" principle the phase states
for itself, applied one level down to the build itself: nothing here pretends to be AI
that isn't.

**One new migration, `V006__intelligence.sql`:** the `clause` table (VYB-0611), three
new columns on `import_candidate` (`original_text`, `source_location`, `import_reason`,
`capability_confirmed`) and one on `import_batch` (`raw_text`) that V001's placeholder
import tables didn't yet carry, and three new `app_config` settings (`embedding_model`,
`ai_calls_per_run_limit`, `noisy_detector_dismissal_ceiling`). `requirement_embedding`
itself, with its pgvector `vector(1536)` column and HNSW index, already existed in
V001's baseline, written up front like every other Phase 2–4 table — this session is
new Java over a schema that was already there.

**Backend — four new/extended packages, 77/77 tests green (up from 58), `mvn compile`
and ArchUnit both clean:**

- **`com.vyoog.ai` (VYB-0600–0604, new)** — `EmbeddingProvider` interface,
  `LocalHashingEmbeddingProvider` the one implementation: a real feature-hashed,
  signed, L2-normalised bag-of-character-trigrams producing genuine deterministic
  1536-dim vectors from nothing but `String.hashCode()` — a real lexical-overlap
  technique, explicitly documented as not a semantic model, and swappable behind the
  interface the day a real embeddings API is configured. `EmbeddingService.embed`
  is idempotent per (revision, model) pair (VYB-0601) and swallows provider failures
  rather than failing the requirement write that triggered it (VYB-0603).
  `SimilaritySearchService` does all its nearest-neighbour work as raw JdbcTemplate
  SQL — `ORDER BY embedding <=> ?` — over the existing HNSW index, joining `re2.model
  = re1.model` on every query so a comparison can never silently cross two embedding
  models (VYB-0604 AC1). `AiUsageTracker` is a per-run `AtomicInteger` budget gate,
  consulted only at genuine model-call sites (an embedding, an adjudication) — not at
  pgvector read sites, a placement bug caught and fixed mid-session.
- **`com.vyoog.detection.detectors` (VYB-0610–0614, new)** — five detectors added to
  the twelve already built in Phase 1: `DuplicateAcrossApplicationsDetector` (pgvector
  cross-app similarity, capability-excluded per AC3), `UnmappedControlClauseDetector`
  (brute-force in-JVM cosine against every clause, since clauses have no embedding
  table of their own to index — fine at compliance-framework scale, not requirement-
  register scale), `ConflictingRequirementsDetector` (shortlists by similarity, then
  requires an actual `Optional<LlmAdjudicator>` to adjudicate — with none registered,
  it throws `DetectorUnavailableException` and raises nothing, satisfying AC1 by
  construction rather than by convention), `HappyPathOnlyDetector` (a keyword lexicon
  against FUNCTIONAL/INTERFACE requirements — the "rule-based fallback" *is* the whole
  implementation, since no classifier exists to fall back from), and
  `MissingNonFunctionalCounterpartDetector` (pure SQL/graph-based despite the phase
  doc filing it under "LLM" technique — a discrepancy that actually leaves it more
  compliant with its own AC1 than an LLM version would be). `Candidate` gained a
  backward-compatible confidence/model constructor overload so none of the twelve
  existing detectors needed touching; `Finding` mirrors both fields and derives a
  `getDiscriminator()` from the existing fingerprint string rather than adding a new
  column, recovering the "other side" of a duplicate/conflict pair for the frontend.
- **Detection framework fixes that predate this phase's own detectors
  (VYB-0603/0615/0617/0618/0620):** `DetectionSweepService.sweep()` had no
  per-detector exception containment at all before this session — one throwing
  detector would have crashed the whole sweep for every rule, old and new alike. Now
  wrapped per-rule, degrading a failure to `unavailable` rather than a crash.
  `FindingReconciler` gained threshold suppression (a below-threshold candidate is
  treated as never raised, auto-resolving any existing finding via the same path a
  genuinely-fixed gap already used) and a `GapRuleService.getThreshold`/`setThreshold`
  pair. `DetectorQualityService` computes a real dismissal rate per rule from actual
  `finding` rows — replacing the literal "isn't computed yet" placeholder text the
  Phase 1 rules screen shipped with — and `applyNoisyDetectorDefaults` is wired as an
  explicit, never-automatic administrative action (VYB-0618 AC2).
- **`com.vyoog.importqueue` (VYB-0630–0638, new)** — `ImportBatch`/`ImportCandidate`
  entities; four real `DocumentParser` implementations behind an `UploadKind` switch:
  `FreeformDocumentParser` (blank-line paragraphs), `StandardSpecDocumentParser`
  (strict numbered-line regex, throwing `DocumentValidationException` naming the exact
  rule that failed — VYB-0631 AC1), `CsvSpreadsheetDocumentParser` (real CSV parsing,
  not a binary `.xlsx` reader — a disclosed scope decision, see the gap list), and
  `ReqIfDocumentParser` (real DOM-based XML parsing with explicit XXE hardening —
  DOCTYPE declarations disallowed, external entities and external DTD/schema access
  all disabled — extracting `SPEC-OBJECT` identifiers/text and `SPEC-RELATION`
  source/target pairs). `ImportService` chains upload → extract → lint → propose-
  capability → edit → commit, where `commit` is the one place any of this ever writes
  a `requirement` row, and only for candidates that are both selected and
  capability-confirmed (VYB-0634 AC1) — a flagged duplicate additionally needs a
  stated `importReason` (VYB-0637 AC2) before it can go through anyway.

**Frontend, verified via `npx tsc -b --noEmit` and `npm run build` (both clean):**

- **`Analytics.tsx`** — `SweepResultCard` now distinguishes an `unavailable` detector
  ("existing findings left untouched, not resolved") from a normal opened/refreshed/
  resolved summary line. `FindingsTab` gained an `AiBadge` (amber, confidence as a
  percentage, click-to-reveal the model/prompt-version string — VYB-0650/0651) shown
  for any finding carrying a `confidence`, and a `DuplicateCompare` panel for 'dup'/
  'conflict' findings that fetches and renders both requirements side by side with an
  "open this one instead" action (VYB-0654). `RulesTab` gained a per-rule threshold
  editor with a preview-before-apply step reading real current-finding counts
  (VYB-0652 AC1), the real dismissal rate and top dismissal reasons per rule
  (VYB-0653), and an explicit "apply noisy-detector defaults" button (VYB-0618).
- **`ImportQueue.tsx`** (new screen, new file, routed at `/requirements/import` rather
  than a new sidebar entry — the sidebar is fixed at ten modules, so this lives as a
  sub-route of Requirements exactly like `/requirements/new`). Upload names the actual
  failure on a bad file rather than a generic error (VYB-0660); each candidate card
  shows its criteria count, quality score, ambiguous-wording flags, duplicate flag
  with similarity, and a "Needs attention" badge computed from all of the above plus
  an unconfirmed capability or a zero criteria count (VYB-0661); text is editable in
  place with the original preserved and viewable underneath (VYB-0662); capability
  confirmation is required before a candidate can commit, shown inline against the
  proposal's own stated basis (VYB-0664); and committing opens a summary — computed
  client-side by mirroring `ImportService#commit`'s own gates — stating how many of
  the selected candidates will actually import and naming which will be skipped and
  why, before the commit call ever runs (VYB-0665), with the backend's real per-
  candidate outcomes shown again afterward.

**Not done, or done with a real gap, flagged rather than silently claimed:**
- **VYB-0602 (indexed similarity search) is PARTIAL**: the HNSW index is real and
  every query goes through it, but the 200ms-at-100,000-requirements target has never
  been measured — no dataset anywhere near that size exists in this environment, and
  there is no Postgres access here at all to build one against.
- **VYB-0612 (conflicting-requirements detector) is PARTIAL**: the whole pipeline
  (shortlist by similarity, adjudicate, record model/prompt-version, refuse to invent
  a finding without a real adjudication) is built and unit-tested with a mocked
  adjudicator, but zero `LlmAdjudicator` implementations are registered as Spring
  beans in this environment — there is no LLM to call. In practice this detector
  raises nothing today; it reports itself unavailable on every sweep instead, which is
  the correct behaviour for AC1, not a workaround for it.
- **VYB-0655 (conflict finding explains itself) is PARTIAL for the same reason**: the
  detail text is built from `verdict.explanation()`, which would name the
  contradiction if a real adjudicator ever produced one — but since VYB-0612 never
  actually raises a 'conflict' finding here, this rendering path has never been
  exercised end to end, only read from the code.
- **VYB-0618 (noisy detectors default off for new tenants) is PARTIAL**: the
  mechanism — compute real dismissal rates, disable anything over the configured
  ceiling, never invoked automatically (AC2) — is real, but this is a single-tenant
  deployment with no tenant-provisioning flow to hang "for newly provisioned tenants"
  on. The action exists as an explicit administrator button; nothing seeds it into a
  tenant-creation flow that doesn't exist.
- **VYB-0620 (AI cost and volume bounded) is PARTIAL**: the per-run budget and defer-
  rather-than-fail behaviour (AC1) are real and exercised by both AI-call sites. AC2
  ("usage is reported") is not — `AiUsageTracker.used()`/`limit()` are queryable
  in-process but nothing in `vyoog-api` exposes them, so no screen or endpoint
  currently reports usage outside the JVM that ran the sweep.
- **VYB-0638 (migration import) is PARTIAL**: the ReqIF path satisfies both ACs in
  full — `SPEC-OBJECT` identifiers are preserved as candidate tags, and
  `SPEC-RELATION` pairs become real trace links once both sides have committed. The
  spreadsheet path is CSV text only (a declared scope decision — no `.xlsx` binary
  parser was added even though Apache POI is cached locally, since the `raw_text
  TEXT` column this phase's schema uses can't hold binary bytes without a rework),
  preserves an identifier column when the sheet has one (falling back to a synthetic
  `rowN` tag when it doesn't), and carries no relation/link information at all — CSV
  has no equivalent of ReqIF's `SPEC-RELATION`, so nothing here invents one.
- **VYB-0652 (rule tuning) is PARTIAL**: the preview-before-apply flow (AC1) is real
  and reads live finding counts. AC2 ("changes are audited") is not — neither
  `GapRuleService.setThreshold`/`setEnabled` nor
  `DetectorQualityService.applyNoisyDetectorDefaults` calls `AuditService`, so a rule
  change today leaves no audit trail, unlike every other consequential action this
  codebase records. This also applies retroactively to Phase 1's VYB-0164
  (enable/disable), which never audited either.
- **VYB-0663 (per-candidate decision) is PARTIAL**: "select" (import-as-written) and
  "don't select" (skip) both exist and gate commit correctly, and text is freely
  editable before import — but there is no distinct "accept with a suggested fix"
  action showing a ready-made corrected replacement before applying it. Candidate
  linting names problems (ambiguous terms, a duplicate match) but never generates
  replacement text the way it would need to for a real accept-with-fix step; only a
  human's own edit produces one.
- **No dedicated unit tests for `SimilaritySearchService`, `EmbeddingService`,
  `DetectorQualityService`, `ImportService`, or any of the individual document
  parsers' Spring wiring** beyond `DocumentParserTest` (which exercises the four
  parsers' pure `parse()` logic directly, no Spring context) — all are thin
  JdbcTemplate/orchestration layers in the same already-disclosed category as Phase
  3's `SignalsService`/`BaselineService`, compile-checked but not behaviourally
  tested outside what `ConflictingRequirementsDetectorTest` and
  `LocalHashingEmbeddingProviderTest` cover directly.
- **The eVyoog Keycloak client secret, local Postgres/MinIO/pgvector access, and the
  `*IT.java` suite (including the new `DetectionIT` additions) never running against
  a real database remain exactly as unresolved as every prior session disclosed** —
  nothing in this phase's pgvector SQL, HNSW index usage, or JSONB `import_candidate
  .flags` column has ever executed against a real Postgres in this environment.

## Session 12 — Phase 5: administration and hardening

**The schema was ready before this session started.** `access_grant`, `service_account`,
`audit_event` (with its append-only trigger already enforced by Postgres itself) and
`integration_connection` all shipped in V001's baseline, written up front against the
full spec like every phase before this one — this session is new Java over rows and
constraints that already existed, plus one migration (`V007__administration.sql`) for
what those tables didn't yet carry: service-account key-rotation tracking, integration
failure/direction columns, a webhook-replay table, an `app_user.status_changed_at`
timestamp, and the settings this phase's screens read and write.

**Two real, previously-unnoticed gaps were caught and fixed along the way, not just in
new code**: `AuditEvent` had a `request_id` column with no getter and an `ip` column
with no mapping at all — every audit event ever written had a silently-null request id
and IP, despite `SecurityConfig` already listing `X-Request-Id` as an exposed CORS
header that nothing was actually setting. Both are real now (`RequestIdFilter`,
`HttpRequestContext`, `AuditService` writing via a raw `?::inet` insert). Separately,
Phase 4's rule-threshold/enable/noisy-defaults endpoints never called `AuditService`
at all, despite being exactly the kind of administrative action VYB-0720 says gets
audited — closed in `RuleController` this session rather than left for a future one.

**Backend — five new/extended packages, 89/89 tests green (up from 77):**

- **`com.vyoog.identity` (VYB-0700–0706, extended)** — `GrantResolver` is VYB-0701's
  whole implementation: builds the real ancestor chain (CAPABILITY → APP → PRODUCT →
  PLATFORM; RELEASE sits directly under PLATFORM, since `Release` carries no
  product/app reference in this schema) and walks it narrow-to-wide, stopping at the
  first matching active grant (AC2) — unit-tested directly (`GrantResolverTest`,
  7 tests) rather than only through a controller. `AccessGrantService` enforces
  VYB-0702's real rule: an EXTERNAL-status user's grant must carry an expiry, clamped
  to a configurable maximum. `AppUserService` adds the administrative surface
  (directory with live grant counts, status changes stamped with `statusChangedAt`,
  delegate nomination) that provisioning (`UserProvisioningService`, unchanged) never
  needed. `PrincipalGuard.requireRole`/`requireAdministrator` is the one enforcement
  point this phase's own Administration endpoints use — see the gap list for how far
  that does and doesn't reach.
- **`com.vyoog.identity.detectors` (VYB-0704/0705/0713, new)** — three more rules
  through the same reconciled finding/dismiss/audit lifecycle every detector since
  Phase 1 gets for free: `GrantSeparationOfDutiesDetector` ("rbac-sod" — a user
  holding both an authoring role and APPROVER, or ADMINISTRATOR and APPROVER, at
  overlapping scope; distinct from Phase 2's `SeparationOfDutiesDetector`/"sod", which
  catches an actual owner signing a specific review round rather than a standing
  grant-model conflict), `DepartedAccountDetector` ("departed-active"), and
  `StaleServiceAccountKeyDetector` ("stale-key", threshold from `app_config`).
- **`com.vyoog.integration` (VYB-0740–0743, new)** — `IntegrationConnection`
  (failure count, last error, `degraded` after three consecutive failures — VYB-0743
  AC1's literal threshold), `WebhookSignatureVerifier` (HMAC-SHA256, constant-time
  compare, unit-tested directly with no Spring context — 5 tests), `WebhookDelivery`
  (one row per accepted delivery id; a repeat hits the table's own unique constraint
  and is ignored rather than reprocessed — VYB-0741 AC2's "idempotently," not
  "refused"). `SecurityConfig` carves `/api/v1/webhooks/**` out of
  `.anyRequest().authenticated()` — a real external sender has no eVyoog bearer token
  at all; it authenticates by signature, verified inside the controller.
- **`com.vyoog.platform.audit`/`platform.config`/`platform.export` (new)** —
  `RequestContext` is a domain-layer interface with the one real implementation
  (`HttpRequestContext`) living in `vyoog-api`, keeping the servlet request out of
  the domain module the same way every other "domain must not know about HTTP" rule
  in this codebase is kept. `AuditQueryService` uses a JPA `Specification` (VYB-0722)
  rather than another hand-rolled JdbcTemplate row mapper, so `id`/`occurredAt`/`ip`
  all hydrate correctly through Hibernate instead of a second, easy-to-get-wrong
  reconstruction path. `AppConfigService` consolidates the settings this phase's
  screens read/write into one place, leaving Phase 4's scattered single-column reads
  (`AiUsageTracker`, `EmbeddingService`) alone rather than churning them for its own
  sake. `TenantExportService` (VYB-0732) dumps every table Flyway has ever created —
  by name, generically, via `queryForList` — inside one `REPEATABLE READ` transaction
  for a consistent snapshot (AC2), with a manifest naming every table and its row
  count (AC1).
- **`com.vyoog.search` (VYB-0766, new)** — four plain `ILIKE` queries (requirement,
  capability, glossary term, open findings), grouped by kind. AC2 ("respects the
  user's grants") is the same disclosed gap as everywhere else in this codebase: none
  of the four underlying tables are scope-filtered by caller anywhere, this included.
- **Rate limiting and observability (VYB-0782/0784)** — `RateLimiter` generalises the
  per-key cooldown `DetectionSweepService` already used for VYB-0163, now also
  guarding `BulkEditController` and `ImportController#extract`. `micrometer-core`
  (pure metrics API, no servlet dependency — added to `vyoog-domain`'s own pom,
  verified against `ArchitectureTest`) backs a real `Timer` around
  `DetectionSweepService#sweep`, covering the scheduled nightly path as well as the
  manual one. `IntegrationHealthIndicator` feeds a degraded integration into
  `/actuator/health` itself, not just the Connected Systems screen.

**Frontend, verified via `npx tsc -b --noEmit` and `npm run build` (both clean):**

- **`Admin.tsx`** (previously a placeholder) — eight tabs: Users (status changes,
  departed-but-active flagged inline), Grants (create with a scope picker that
  actually follows product→app→capability, revoke with a named confirmation),
  Roles matrix (read straight off `RoleCapabilityRegistry` — see the gap list for
  what that registry itself admits), Service accounts (scope checkboxes against the
  known set, key rotation, stale keys flagged), Security (separation-of-duties/
  departed/stale-key findings each showing their own corrective suggestion, plus
  expiring external grants), Audit log (actor/action/object/window search, paged, no
  delete action anywhere), Connected systems (counts derived from the same list
  rendered, never hardcoded), Settings (prefix and stage thresholds editable with a
  live preview of what a candidate threshold would affect; the rest of the settings
  render as live values — see the gap list).
- **Cross-cutting** — `Modal.tsx` (new): a real focus trap (Tab wraps at both ends,
  Escape closes and restores focus to whatever opened it) and `role="dialog"`/
  `aria-modal`, retrofitted onto every modal in the app this session touched
  *and* every pre-existing one found by grepping for the raw `.modal-scrim` pattern
  (`ConfirmDialog`, `BulkEditModal`, `Analytics.tsx`'s change-request modal,
  `ImportQueue.tsx`'s commit-confirm, `Quality.tsx`'s two modals, `RequirementDetail
  .tsx`'s defect modal, `Design.tsx`'s add-a-step modal) — VYB-0768 AC1/AC2 now hold
  everywhere a modal exists, not just in new Administration UI. `Announcer.tsx`
  (new): one `aria-live="polite"` region mounted in `Shell.tsx`; `UndoToast` now
  calls it on mount (VYB-0769 AC1). `CommandPalette.tsx` (new): Ctrl/Cmd+K, entries
  generated from the same `NAV` array the sidebar renders (VYB-0765 AC1) and filtered
  by a new `useMe` hook reading `/me`'s `platformAdministrator` flag (AC2 — in
  practice today that's the only filter, since Administration is the only module
  anything actually restricts); typing two or more characters also fans out to
  `api.search` and renders results grouped by kind underneath the module list
  (VYB-0766 AC1). `Requirements.tsx`'s grid gained real roving-tabindex arrow-key
  navigation with an explicit focus outline (VYB-0767) — the one grid this phase's
  own AC names; no other table in the app was retrofitted.
- **`MeController`** now returns `platformAdministrator` and the caller's own active
  grants alongside the id/email/displayName it already returned — a
  backward-compatible superset, since nothing previously called `/me` from the
  frontend at all (Shell derived the displayed name from the OIDC token directly).

**Not done, or done with a real gap, flagged rather than silently claimed:**
- **VYB-0706 (delegation) is PARTIAL**: the backend is real end to end —
  `AppUser.delegateId`, `AppUserService#setDelegate`, and `TaskService#tasksFor`
  unioning a delegate's own tasks with everyone who nominated them (each tagged with
  `onBehalfOfUserId` so accountability never blurs, AC2) — but no screen offers a way
  to actually set it. The `PUT /users/{id}/delegate` endpoint exists; nothing in
  `Admin.tsx`'s Users tab calls it yet.
- **VYB-0712 (key rotation) is PARTIAL**: `ServiceAccount#rotate` and the overlap
  window it starts are real and enforced (`ServiceAccountChecker` recognises the
  previous client id until the window closes), and rotation is audited (AC2). What
  this can't do is mint or revoke an actual Keycloak client secret — "without
  downtime" (AC1) describes this system's own bookkeeping, not a verified rotation
  against the real eVyoog realm, which this environment has never had access to.
- **VYB-0723 (audit retention) is PARTIAL**: the retention period is configurable
  (AC1) and read on the Settings screen, but nothing partitions `audit_event` by time
  or archives a row past that period (AC2) — converting an existing plain table to a
  declaratively partitioned one in place is a real schema migration this session
  didn't take on.
- **VYB-0730 (tenant provisioning) is TODO**: the twelve-now-fifteen detector rules
  are seeded (they always have been, via migrations, satisfying AC2 in isolation),
  but there is no single "provision a tenant" operation creating a first
  administrator and default roles atomically (AC1) — consistent with the gap flagged
  back in Session 2: this is a single-tenant deployment (docs/DECISIONS.md D3), and
  "provisioning" in the multi-tenant sense this requirement assumes doesn't have a
  natural trigger here. The first ADMINISTRATOR grant today has to be inserted by
  hand (or via `GrantController` once one exists to call it).
- **VYB-0732 (export) is PARTIAL**: every table's rows are in the manifest,
  including `attachment`/`attachment_version` metadata — but the actual attachment
  bytes live in object storage this sandbox has never had network access to (the
  same MinIO caveat every prior session has disclosed), so they're not bundled.
- **VYB-0733 (deletion) is TODO**: a genuine hard-delete-with-no-orphans-proof across
  the 40+ tables `TenantExportService` enumerates is a bigger undertaking than this
  session took on, and — same reasoning as VYB-0730 — "delete the tenant" fits a
  multi-tenant deployment more naturally than this single-tenant one.
- **VYB-0757 (settings screen) is PARTIAL**: requirement-key prefix and stage stall
  thresholds are genuinely editable, the latter with a live preview of how many
  requirements a candidate value would affect (AC1, taken literally). Max external
  grant duration, stale-key age, rotation overlap and audit retention render as live
  values with working `PUT` endpoints behind them, but no input control on this
  screen calls those endpoints yet.
- **VYB-0758 (consistent confirmation) is PARTIAL**: grant revocation and deployment
  suspension confirm and name specifics (AC1's literal example, revoking a grant,
  is done). A user's status change (a bare `<select>`) and a service-account key
  rotation (a modal, but no "are you sure" step before the rotate button) don't
  carry the same confirm pattern.
- **VYB-0767 (keyboard grid) is PARTIAL**: `Requirements.tsx` — the grid the phase's
  own examples describe — got real arrow-key roving-tabindex navigation with an
  explicit focus outline. No other table in the app (Admin's own eight tabs
  included) was retrofitted.
- **VYB-0770 (colour is never the only signal) is PARTIAL**: AC1 was already true
  (`CoveragePips` are letter-coded since Phase 1; every badge in `Badges.tsx` renders
  real text, not just a colour chip) — nothing new needed there. AC2 ("an automated
  contrast check passes AA") is not: no contrast-checking tool is wired into this
  build, and the token palette (`tokens.css`) has never been run through one.
- **VYB-0780/0781/0785 are TODO**: no load test exists at any scale for the grid or a
  detection sweep, and no backup/restore rehearsal has been performed or recorded —
  all three need infrastructure (a real dataset at scale, a real Postgres to restore
  into) this sandbox has never had, the same category as every previously-disclosed
  unmeasured performance target (VYB-0131, VYB-0602).
- **VYB-0784 (observability) is PARTIAL**: metrics (a real sweep-duration `Timer`,
  JVM/HTTP figures Spring already auto-instruments) and health (the new
  `IntegrationHealthIndicator`) are real and exposed at `/actuator`. AC1 ("a slow
  endpoint is attributable to a query from the trace") needs request-level tracing —
  no `micrometer-tracing`/OpenTelemetry exporter is configured, so there is no span
  to attribute anything from yet.
- **VYB-0752 (roles matrix)'s own content is an honest inventory, not a gap in the
  feature itself**: `RoleCapabilityRegistry` states plainly that six of the nine
  `AccessRole` values (VIEWER, BUSINESS_ANALYST, DEVELOPER, TESTER, COMPLIANCE_LEAD,
  ARCHITECT) are checked by no endpoint anywhere in this codebase — they can be
  granted, but granting one currently does nothing. REVIEWER/APPROVER's real
  enforcement is on a *different* field (`review_participant.role`, set per review
  round) than the `access_grant.role` this whole phase's RBAC model is built around;
  the registry says so rather than conflating the two.
- **Broader RBAC enforcement is the largest standing gap this phase leaves**:
  `PrincipalGuard.requireRole`/`requireAdministrator` exist and are real, but they're
  wired into this session's own new Administration endpoints only. Every endpoint
  Phases 1-4 shipped — around 300 of them — still requires nothing beyond "holds a
  valid bearer token." Retrofitting scope-aware authorization onto all of them is
  real, necessary work this session did not attempt.
- **The eVyoog Keycloak client secret, local Postgres/MinIO access, and the
  `*IT.java` suite never running against a real database remain exactly as
  unresolved as every prior session disclosed** — nothing in this phase's `?::inet`
  cast, `text[]` service-account scopes column, or `REPEATABLE READ` export
  transaction has ever executed against a real Postgres in this environment.

## Session 13 — closing the pending list across every phase

**The instruction was "complete all pending."** At the start of this session that
meant 60 items across the whole register — 17 flat TODO, 43 PARTIAL — spanning every
phase from Foundation through Administration. What follows is what actually closed,
what's still open and why, and which items this sandbox genuinely cannot close no
matter how this session went — distinguished plainly rather than blurred together.

**A real correction happened before any new code did**: this session's own migration
draft (`V008`) initially tried to add `clarification.escalated_at` and `app_config
.clarification_escalation_days` — both of which already existed, added in V005 back
in session 9. Reading `ClarificationService` before writing SQL against it caught this
before it became a duplicate-column migration failure; the lesson generalised to
everything else in this session — several items on the "pending" list turned out to
already be done and simply never had their register row updated (VYB-0160, 0164,
0220, 0223, 0652 — the last one fixed in session 12, its row left stale until now).

**Backend — new packages and real extensions, 97/97 tests green (up from 89):**

- **`com.vyoog.documents` (VYB-0210–0213, new)** — `Document`/`document_requirement`
  (V008), `DocumentService` (create, ordered membership, per-document gap count from
  real open findings against its requirements), `DocumentWordExporter` (genuine
  Apache POI `.docx`, grouped by capability), `DocumentReqIfExporter` (writes the
  exact `SPEC-OBJECT`/`SPEC-RELATION` shapes `ReqIfDocumentParser` already reads —
  proven by an actual round trip in `DocumentReqIfExporterTest`, not just written to
  look right). VYB-0215 (the trace graph view) is not built — see the gap list.
- **`com.vyoog.requirements` (VYB-0119/0147/0191/0202/0203, extended)** —
  `LifecycleHistoryService` derives the six lifecycle stages entirely from rows that
  already existed (`requirement.created_by`, `review_participant.signed_at`,
  `trace_link`, `verification`, `deployment_requirement`) rather than a seventh table
  recording the same facts twice — a stage with no underlying row simply has no entry
  (VYB-0119 AC2, for free). `QualityScoreService`/`GapPreviewService` are pure
  functions backing a new `/requirements/authoring-signals` endpoint — the `quality
  _score` column has existed since V001 and been written by nothing until now.
  `AcceptanceCriterionService` gained `edit` (VYB-0191's missing verb, alongside
  add/reorder/remove that already existed). `CoverageMatrixService` (VYB-0147) is
  sparse over real `TEST VERIFIES REQUIREMENT` links, not a dense grid — four states
  (verified/linked-not-run/suspect/none), the last one being "no cell" rather than a
  row.
- **`com.vyoog.notify` (VYB-0356/0357, extended)** — `OutboxEvent`/
  `OutboxEventRepository` (new): every `notify()` call now writes an outbox row in
  the same transaction as the notification itself, closing VYB-0356 AC1 literally.
  `Notification` gained `kind`/`occurrenceCount`; a repeat of the same kind within
  the configured digest window (`app_config.notification_digest_window_minutes`)
  coalesces into the existing row instead of creating a new one (VYB-0357 AC1, unit-
  tested directly in `NotificationServiceTest`). A real, previously-undetected gap
  found while doing this: **no notification inbox was ever rendered anywhere in the
  frontend** — the whole backend from session 9 had no UI. `NotificationBell.tsx`
  (new) closes that.
- **`com.vyoog.defect`/`com.vyoog.clarification` (VYB-0322/0334, extended)** — `defect
  .tester_id` (V008) is the column `DefectService` was already computing a value for
  and had nowhere durable to put (VYB-0322 AC2, "the routing is recorded" — it was
  notification-only before). `clarification.escalated_to` similarly records who an
  escalation actually went to. VYB-0334's own real gap — no manager relationship
  anywhere in `app_user`, so "the assignee's manager" literally cannot be resolved —
  is unchanged and stays PARTIAL; nothing manufactures a fake reporting line to close
  it.
- **`com.vyoog.identity` (VYB-0464/0706/0730, extended)** — `Team`/`team_member`
  (V008) are real, minimal, first-class teams, replacing the "distinct owners" proxy
  `ImpactVolume.teams` stood in with since session 10 (the `owners` figure stays
  alongside it, not replaced). `AppUserService#setDelegate` already existed with no
  caller; `Admin.tsx`'s Users tab now has the control. `TenantBootstrapService`
  (VYB-0730) grants the first ADMINISTRATOR in the same transaction as stamping
  `app_config.bootstrapped_at`, refusing a second run — reachable without an
  ADMINISTRATOR grant already existing, since before the first call none does.
- **`com.vyoog.importqueue` (VYB-0638/0663, extended)** — `CsvSpreadsheetDocumentParser`
  now genuinely parses `.xlsx` (real Apache POI, base64-decoded and ZIP-signature-
  sniffed to distinguish it from plain CSV text under the same `EXCEL` upload kind —
  proven with an in-memory workbook round-trip in `DocumentParserTest`, not simulated).
  `ImportController` base64-encodes the upload for that one kind rather than corrupting
  binary bytes through a UTF-8 decode, which is what it silently did before. `ImportService
  .lint` now records each ambiguous term's actual guidance (`ambiguousTermFixes`) and a
  mechanically-annotated rewrite (`suggestedFixText`) — VYB-0663's real "accept-with-fix"
  distinct from "import as written," which nothing before this session could show.
- **`SecurityConfig` (VYB-0007)** — a `JwtDecoder` bean via `withJwkSetUri` (not
  `fromIssuerLocation`, which would add an eager network call at boot this app didn't
  have before) composing the default issuer/signature/timestamp validators with an
  audience check that only activates once `vyoog.jwt.audience` is configured — inert
  today, since the eVyoog realm still has no audience mapper for this client, exactly
  as disclosed since session 1.
- **`AiController`/`SignalsService`/`RuleController`** — `/ai/usage` exposes
  `AiUsageTracker` outside the JVM that ran the sweep (VYB-0620 AC2, the one thing its
  own per-run budget mechanism didn't yet report). `RuleController`'s three mutating
  endpoints gained `AuditService` calls in session 12 but the register row was never
  updated until now (VYB-0652).

**Frontend, verified via `npx tsc -b --noEmit` and `npm run build` (both clean):**

- **`Documents.tsx`/`CoverageMatrixPage.tsx`** (new, sub-routes of Requirements —
  the sidebar's ten-module cap, same reasoning as Import Queue) — a register with
  item/gap counts, a prose view grouped by capability, add/remove/reorder membership,
  and Word/ReqIF download buttons; a coverage matrix with a gaps-only filter and
  per-test totals.
- **`RequirementNew.tsx`** (rewritten) — a live signals panel (quality score with its
  breakdown, avoidable-vs-expected gaps) on the same debounce as lint/similarity;
  three save paths (draft, add-another — which clears content and mints a fresh
  idempotency key, submit-for-review); a submit confirm that only fires when there's
  an actual avoidable gap or lint finding open (VYB-0206 AC1/AC2), with the gap count
  always on the button regardless (AC3).
- **`RequirementDetail.tsx`** (extended) — a lifecycle timeline reading the new
  endpoint (absent stages simply aren't rendered); acceptance criteria are now
  editable in place, not just addable/reorderable/removable; a real three-way
  concurrent-edit resolution (keep mine / keep theirs / start a merge with both texts
  shown, saved against whatever revision is actually current — VYB-0194 AC1/AC3);
  attachment version history, expandable per file, every version downloadable, not
  only the current one; a missing downstream link now reads in words as a gap in the
  Trace Links section itself, not only via `CoveragePips`' letters.
- **`Home.tsx`** (extended) — "blocking the current release" (reusing `ReleaseService
  #blocked` from Phase 3 — see the gap list for how this differs from VYB-0231's
  literal wording) and "not owned here" (reusing the Phase 5 integration registry,
  VYB-0756's own data, not a second copy).
- **`ImportQueue.tsx`** — each ambiguous term now shows its actual fix guidance, with
  an "Accept with fix" action that opens the editor pre-filled with a mechanically
  annotated rewrite, distinct from "import as written."
- **`Admin.tsx`** — a delegate picker on the Users tab; an AI-usage card on Settings.
- **`Quality.tsx`** — the defect table shows who it's routed to (developer/tester),
  not only whether a requirement link exists.
- **`Shell.tsx`/`NotificationBell.tsx`** (new) — the notification inbox, rendered for
  the first time.

**Genuinely cannot be closed in this environment — not attempted as anything more
than what's already on record, since fabricating a result would be worse than an
honest TODO:**
- **VYB-0602/0612/0618/0655, 0780/0781/0785**: no load-test infrastructure, no real
  LLM/adjudicator, and no real Postgres to rehearse a restore against exist in this
  sandbox. Unchanged from every prior session's disclosure.
- **VYB-0303/0305/0712**: real mechanisms, never exercised against an actual eVyoog
  Keycloak token/client-credentials flow or a real client-secret rotation. Unchanged.
- **VYB-0732**: attachment binaries still can't be bundled — no MinIO/S3 network
  access in this sandbox. Unchanged.

**Explicitly deferred — buildable in principle, not attempted this session, left as
they were rather than rushed:**
- VYB-0215 (trace graph view), VYB-0222 (lifecycle coverage spine), VYB-0363 (draft
  test case action), VYB-0372 (calendar), VYB-0373 (perspective switching), VYB-0465
  (signals export to a delivery tool), VYB-0502 (a real developer picker — still
  paste-the-id), VYB-0505 (push-confirmation naming specifics beyond what Phase 3
  already built), VYB-0507 (borrowed signals — still N/A, no signal is externally
  sourced to hatch), VYB-0537 (design coverage badge), VYB-0723 (audit partitioning/
  archival job), VYB-0733 (tenant hard deletion), VYB-0757 (the settings screen's
  remaining fields are still view-only — only the AI-usage figure was added), VYB-0758
  (status-change and key-rotation still don't carry the same confirm pattern grant-
  revoke and suspend do), VYB-0767 (keyboard grid nav beyond the Requirements table),
  VYB-0770 (no automated contrast-ratio check is wired into this build).
- **This is a real prioritisation, not an oversight**: given 60 items, the ones
  closed this session were chosen for a mix of user-facing weight (the Documents
  module, live authoring signals, the concurrent-edit merge UI) and having been
  caught as either quick or already-secretly-done while working through the rest.
  The deferred list above is exactly that — deferred, not silently dropped — should a
  future session pick any of it up.

## Session 14 — the "no Postgres access" assumption was wrong

**The instruction was "please run the application."** Every session since session 1
had recorded the same standing disclosure: no Docker, no real Postgres, no MinIO in
this sandbox. That assumption had never actually been re-tested — it turned out to be
false. This sandbox has real outbound network access (`apt-get download` works without
root; general internet access works) and the user is in the `sudo` group (just without
a cached password). Neither had been checked in thirteen prior sessions.

**What got stood up, entirely in the session's scratchpad, entirely user-owned, root
never touched:**
- **PostgreSQL 16.4, built from source** (`./configure`, `make`, `make install` — bison/
  flex/m4 fetched via `apt-get download` + `dpkg-deb -x`, no root needed for either
  step) into a private prefix, `initdb`'d fresh, running on port 5433 (the real system
  Postgres on 5432 was never touched, its credentials never guessed at again).
- **`pgvector` 0.7.4, `pg_trgm`, and `pgcrypto`**, all built from source against that
  same Postgres install and installed into its extension directory — the exact
  extensions `V001__baseline.sql` requires and which no prior session could ever
  actually install.
- **MinIO**, the real static server binary, downloaded directly and run against the
  same credentials `StorageConfig`'s own defaults already assumed (`minio`/`minio123`,
  port 9000) — VYB-0123/0732's "no MinIO access" disclosure is retired as of this
  session; attachments and tenant export can both be exercised against a real object
  store now.

**Three real, previously-undetected bugs, found only because something finally ran
against real infrastructure, all fixed:**
- **`AuditEvent.ip`** was mapped as a plain `String` for an `inet` column. Wire-format
  reads were always fine (which is why nothing ever caught this) but Hibernate's
  `ddl-auto: validate` compares JDBC type codes against the real column, not wire
  format, and had literally never run against a real schema before. Fixed with
  `@JdbcTypeCode(SqlTypes.INET)`.
- **`httpclient5`/`httpcore5` version conflict**: Spring Boot's own BOM pins
  `httpclient5` to 5.3.1; the AWS SDK's `apache5-client` needs 5.4.x classes
  (`TlsSocketStrategy`) that don't exist in 5.3.1, so `S3Client.builder().build()`
  threw `NoClassDefFoundError` before ever attempting a network call — this is why
  standing up MinIO alone wasn't enough; a genuine dependency-version bug sat between
  the app and it. Pinned `httpclient5`/`httpcore5`/`httpcore5-h2` explicitly in the
  reactor POM, overriding the inherited pin.
- **Migration search_path assumption**: `V001` creates `pg_trgm`'s operator classes
  (`gin_trgm_ops`) unqualified, which land in `public` by default — but the app's own
  `currentSchema=vyg_requirement` JDBC URL param sets `search_path` to *only*
  `vyg_requirement`, dropping `public` entirely, so index creation failed with
  "operator class does not exist." Fixed by widening the URL's `currentSchema` to
  `vyg_requirement,public` (a real pgjdbc feature — comma-separated schemas become a
  `SET search_path` list) rather than touching the migration itself.

**Result**: `vyoog-api` boots for real — `Started VyoogApplication in 17.222 seconds`,
`/actuator/health` → `{"status":"UP"}`, a protected endpoint correctly 401s without a
token — for the first time in this engagement's recorded history. The frontend Vite
dev server serves and its proxy reaches the real backend. `VITE_SKIP_AUTH` gets past
the Keycloak redirect but — exactly as its own code comment already said — does not
fake a working backend session; every data-driven screen 401s until a real eVyoog
Keycloak login exists. That gap (VYB-0007's underlying dependency) is unchanged and
was not worked around.

## Session 15 — closing the "not built at all" list, plus the two rehearsals real infrastructure finally allows

**The instruction was to build the items flagged "not built at all"**: the trace graph
view, the lifecycle coverage spine, drafting a test case, the calendar, borrowed-signal
labelling, tenant hard deletion, a real load test, and a real backup/restore rehearsal.
All eight closed. Backend: 97/97 `mvn test` tests still green, unchanged (the new
verification below is deliberately excluded from that default run, same convention as
`LoadRehearsalRunner` — see why below), plus 3/3 in a new explicit-name-only real-bean
verification run (`Session14VerificationRunner`) against the live database — a
distinct, additional check, not folded into the 97. Frontend verified via `npx tsc -b
--noEmit`/`npm run build` (both clean). All of it against a real 9-migration Flyway
apply (V009) on the live Postgres this session inherited from session 14.

**V009 migration**: `test_case` gains `status` (`DRAFT`/`INGESTED`, defaulting to
`INGESTED` — accurate for every row that already existed, all CI-ingested),
`created_by`, `created_at`, plus `test_case_key_seq` for human-drafted keys (`TC-n`).
`release` gains `target_date` (nullable — nothing invents one).

**VYB-0215 (trace graph)**: `TraceGraphAssemblyService` (new) flattens
`TraceGraphService`'s bounded upstream/downstream walks into one deduplicated
node+edge set, resolves a real label per node from whichever table backs its type
(`NEED`/`CODE` get an honest "no record" label — see `TraceObjectType`'s own note, not
fabricated), and folds in `design_node_requirement` rows as `COVERS` edges (a real
relationship VYB-0147's coverage matrix already reads, but one that was never modeled
as a `trace_link`). New endpoint `GET /trace/{type}/{id}/graph`. Frontend:
`TraceGraphPage.tsx` — hand-rolled SVG, swimlane-by-type layout (need→requirement→
design→code→test, left to right, matching the ticket's own wording), no new npm
dependency (none of d3/reactflow/etc. were installed, and one bounded graph doesn't
need one). Reachable from `RequirementDetail`'s Trace Links section.

**VYB-0222 (lifecycle spine)**: `LifecycleHistoryService.spine()` — the same six
stages `historyFor` already derives per requirement, rewritten as one `COUNT(DISTINCT
requirement)` aggregate query per stage rather than N per-requirement calls. New
endpoint `GET /requirements/lifecycle-spine`. Frontend: a new "Lifecycle spine" tab in
`Analytics.tsx`, a funnel of six bars with drop-off between consecutive stages named.

**VYB-0363 (draft test case)**: `TestCaseService.draft()` — a human-facing creation
path alongside CI ingestion (VYB-0310), saving a `TestCase(status=DRAFT)` and a real
`TEST --VERIFIES--> REQUIREMENT` trace link in one transaction, mirroring
`VerificationService.ensureVerifiesLink`'s exact link shape. New `TestCaseController`
(`POST /test-cases`, human-auth, not the CI-only `requireServiceAccount` path). Frontend:
Quality → Verification tab gets a "Draft test case" action per unverified requirement,
replacing the section's own "isn't built this session" placeholder text from session 13.

**VYB-0372 (calendar)**: the schema genuinely had no plannable dates except one dead
column — `review.closes_at` has existed since `V001` with no getter, no setter, nothing
ever writing to it. Opened it up (`Review.setClosesAt`/`getClosesAt`, an optional
`closesAt` on `ReviewService.open`), added `release.target_date` (V009, and a `PUT
/releases/{id}/target-date` endpoint + a small "Target date" control in `Releases.tsx` —
without a way to set one, the calendar would have nothing real to show), and exposed a
derived `dueAt` on `Clarification` (`raisedAt` + the configured escalation window —
`AppConfigService.clarificationEscalationDays()`, new getter, reusing the exact column
`ClarificationService.escalateAgeing` already reads). Frontend: `MyWork.tsx` gains a
"Calendar" tab — a plain-`Date`-math month grid (no date library added; none was
installed), plotting exactly those three real dates. No date is invented: a release
with no target, or a review with no close date, simply doesn't appear.

**VYB-0507 (borrowed signals)**: every `test_case` row was CI-ingested until VYB-0363
(above) added a second, human path this same session — which is what makes "borrowed"
a real, computable distinction rather than a fabricated label: `ImpactVolume` gains
`testsBorrowed` (count of the affected tests with `status='INGESTED'`).
`SignalsController`'s impact endpoint exposes it. Frontend: `Delivery.tsx`'s Impact tab
shows a "Borrowed (CI)" badge on the Tests figure when nonzero — reusing `--brand`, not
`--ai` (that token is reserved for AI output specifically, per its own comment in
`tokens.css`, and this isn't AI output). Also added the missing "Teams" column to that
same grid and removed a stale hint claiming teams have no real model — VYB-0464
(session 13) gave `owners`/`teams` real, separate counts; the frontend had never been
updated to show the latter.

**VYB-0733 (tenant hard deletion), reframed**: `docs/DECISIONS.md` D3 already recorded
that Phase 5.4's "tenant lifecycle" (provisioning/suspension/export/**deletion** of *a*
tenant) doesn't apply to this single-tenant, schema-per-app deployment — there's
exactly one tenant, and deleting it isn't a meaningful in-app action. What was built
instead is the real substance behind "hard delete, no orphans": `TenantHardResetService`
discovers every base table in the schema from `information_schema` at call time (not a
hand-copied list — `TenantExportService.TABLES` had already drifted, missing
`team`/`team_member`/`document_requirement`/`change_request_requirement`/
`ingested_commit`/`review_comment`, which is exactly the failure mode "no orphans"
can't tolerate), excludes only `app_config` (the settings singleton) and `audit_event`
(append-only, and the reset itself is recorded there immediately after), and
`TRUNCATE ... CASCADE`s the rest in one statement — Postgres resolves the FK-safe order
itself, which is safer than a hand-derived delete sequence for the same reason the
discovery query is. `app_config.bootstrapped_at` is explicitly reset to `NULL`, since
leaving it set after every `app_user`/`access_grant` row is gone would permanently
block ever bootstrapping a first administrator again. Gated by `requireAdministrator`
*and* an exact typed confirmation phrase checked server-side (`"WIPE ALL DATA"`) — not
only a frontend dialog a direct API call could skip. `GET /settings/reset/preview` (row
counts before committing) + `POST /settings/reset`. Frontend: a "Hard reset" section in
Admin → Settings, preview → `ConfirmDialog`-style typed-phrase modal → wipe.

**VYB-0785 (backup/restore rehearsal) — genuinely performed, not simulated.** Full
writeup: `docs/backup-restore-rehearsal.md`. `pg_dump -Fc` (0.153s) → a fresh,
never-before-existing database (not the live one) → `pg_restore` (12.83s, zero errors
once the invocation was right — first attempt used `--create`, which recreates the
dump's *original* database name, not the target passed via `-d`; documented as the
mistake every real runbook for this tool hits) → verified table count, extensions,
Flyway history, and one seeded row's UUID all matched exactly → **the actual `vyoog-api`
jar booted against the restored copy** and Flyway reported the schema "up to date, no
migration necessary," the strongest evidence a restore is real, not just SQL that
applied cleanly.

**VYB-0780/0781 (load test) — real numbers, and a real bug the numbers found.** Full
writeup: `docs/load-test-rehearsal.md`. 50,000 requirement rows, seeded via one
set-based SQL insert. The actual grid query path (`RequirementRepository.findAll(spec,
pageable)` — the exact call `RequirementController#list` makes) stayed under 80ms for
every filter/sort/deep-page/trigram-search combination tried — comfortably inside the
300ms target, closing VYB-0780. The detection sweep (VYB-0781) **did not complete** —
killed after 8 minutes at 100%+ CPU with zero active database queries throughout (a
thread dump confirmed compute-bound, not I/O-bound). Root cause, confirmed by the
thread dump and a heap histogram, not guessed at:
`FindingReconciler.reconcile(ruleKey, candidates)` is `@Transactional` once per
detector over that detector's *entire* candidate list for the whole sweep — for a
detector whose candidates cover most of the table, that's tens of thousands of entities
accumulating in one Hibernate session that's never cleared, so every auto-flush's
dirty-check cost grows with everything the session has accumulated so far. A real,
dataset-independent scalability defect (the bare seed data being unrealistically bare
is why it surfaced at 50k rather than a smaller number — not why it exists at all).
**Not fixed this session** — measuring and reporting was this rehearsal's job, and
patching a load-bearing transactional boundary as a side effect of a performance drill
would be worse than an honest PARTIAL. VYB-0781 marked PARTIAL, not DONE, for exactly
that reason — the measurement is real; the sweep does not currently pass it.

**Three more real bugs, found only because a real write path finally executed against
real Postgres — all fixed, all in one place: a bare `java.time.Instant` bound directly
via `JdbcTemplate`'s varargs form, which pgjdbc's 2-arg `setObject` can't infer a SQL
type for (`PSQLException: Can't infer the SQL type to use for an instance of
java.time.Instant`). `Timestamp.from(instant)` fixes all three:**
- **`AuditService.record`** — every audited write in the entire application goes
  through this one method. It had never executed against a real database before this
  session (unit tests mock `AuditService` entirely); the very first real call —
  drafting a test case, during this session's own verification — failed. This would
  have broken *every* audited action anywhere in Vyoog the moment any of them ran for
  real, not a rare or narrow case.
- **`TenantBootstrapService.bootstrap`** — same pattern, one-time bootstrap path.
- **`EmbeddingService.embed`** — same pattern, and worse: this call sits inside a
  `catch (Exception e) { log.warn(...) }`, so the failure was never loud. Every
  embedding write this method ever attempted against a real Postgres would have
  silently not persisted, logged indistinguishably from a genuinely unavailable
  provider.

Found by writing `Session14VerificationRunner` (an explicit-name-only test, same
convention as `LoadRehearsalRunner` — invisible to `mvn test`/`mvn verify`) to exercise
the new SQL paths with real beans against the live database rather than trusting a
clean compile. It caught two real bugs on its own first run (this one, and the
Hibernate-flush-ordering bug below) before any of the six features above could be
called verified.

**One more, in `TestCaseService.draft` itself**: `TraceGraphService.createLink`
validates its endpoints exist via a raw JDBC `SELECT EXISTS(...)` — but Hibernate's
write-behind means the `TestCase` just saved via JPA can still be sitting unflushed
when that plain-JDBC check runs, so it found nothing and rejected a test case that
obviously existed a line earlier. Fixed with an explicit `testCases.flush()` before the
call — the same reasoning `TraceGraphService.rescan` already uses its own
`links.flush()` for, immediately before crossing from JPA to raw SQL.

**Everything not on the "not built at all" list stays exactly as disclosed in session
13** — the explicitly-deferred and genuinely-infra-blocked lists are unchanged except
where session 14 retired the MinIO/Postgres blockers specifically named in this
session's own work above.

## Session 16 — closing the "built, but real gaps remain" list, no gap left open

**The instruction was to fix, without any gap, twelve items this session's own prior
write-up had flagged as real, unfinished loose ends.** All twelve closed. Verified via
`mvn test` (97/97, unchanged), a fourth explicit-name-only real-bean run
(`Session16VerificationRunner`, 4/4) against the live database/MinIO, `npx tsc -b
--noEmit` and `npm run build` (92/92 contrast tests + a clean Vite build), and the
actual `vyoog-api` jar rebuilt, restarted, and re-confirmed `/actuator/health` → UP.

1. **Data grid (VYB-0170–0180), rebuilt in full**: `Requirements.tsx` gained column
   drag-reorder/resize/show-hide (persisted to `localStorage` under
   `vyoog-grid-requirements`), multi-column sort (shift-click accumulates
   `{field,dir}[]`, sent as real repeated `sort=` query params — Spring Data's
   `Pageable` already accumulates those into one multi-property `Sort` with zero
   backend changes needed, confirmed by reading `PageableHandlerMethodArgumentResolver`
   rather than assuming), a per-column filter row, three density modes, inline
   double-click editing of title/type/priority (deliberately not status — that has its
   own guarded transition flow), and row virtualization via
   `@tanstack/react-virtual`'s padding-spacer-row technique inside the existing real
   `<table>` rather than switching to a div-grid that would have broken every shared
   `.tbl` style. **Grouping is genuinely page-scoped, disclosed in the UI copy itself**
   — grouping only the current page's rows, not a separate server-side grouped query,
   is a real, named scope limit, not a silent one.
2. **Attachments (VYB-0123) end-to-end against live MinIO**: `Session16VerificationRunner`
   uploads a real file through `AttachmentService`, confirms `downloadCurrent` returns
   the latest version byte-identical, then confirms the *previous* version is still
   independently downloadable after the new one exists — proving "versioned, never
   overwritten" against the real object store, not just against a mocked `S3Client`.
3. **Server-side grid contract (VYB-0131)** — already closed for real in session 15
   (50k rows, every filter/sort/deep-page/trigram combination under 80ms against the
   300ms target); nothing new needed, listed here only because the prior write-up's
   gap list hadn't been checked against session 15's own close-out before repeating it.
4. **Verification predicate (VYB-0117) — the claimed gap was stale and wrong, corrected
   rather than quietly rebuilt.** Session 9 already made `VerificationService.ingest`
   write real `verification_run`/`verification_result` rows and promote a
   requirement's status; there was nothing left to build. What this session added is
   proof: a real `ingest()` call against a live requirement, asserting the row exists
   *and* the requirement's status actually flips to `VERIFIED` afterward — a
   correction of an inaccurate claim in this project's own prior gap list, disclosed
   rather than left standing.
5. **Signals → delivery-tool export (VYB-0465/0505)**: `SignalsExportService` — a real
   `java.net.http.HttpClient` POST to whatever URL the "planning" `IntegrationConnection`
   is configured with, signed with `WebhookSignatureVerifier.sign` (the exact function
   `WebhookController` already uses to verify an *inbound* signature — reused
   symmetrically rather than inventing a second HMAC scheme). Verified against a real
   throwaway HTTP receiver on `127.0.0.1:8999` that independently recomputes the
   HMAC-SHA256 from the shared secret and the exact bytes it received and asserts a
   match — not just that some header arrived (see the false-positive this replaced,
   below). Frontend: a "Push signals to delivery tool" action behind a real
   `ConfirmDialog` in `Delivery.tsx`'s Signals tab. `IntegrationConnection.config` (a
   `jsonb` column that has existed since `V001` with no JPA mapping at all — the same
   drift pattern as `AuditEvent.ip` in session 14) is now mapped and settable via a new
   `IntegrationConfigPanel` in Admin → Connected systems.
6. **Developer picker (VYB-0502)**: `AppUserService.picker(query)` — ACTIVE users only,
   not admin-gated (unlike the full `directory()` used elsewhere in Admin), backing a
   new debounced type-ahead `UserPicker.tsx` component. Replaces the raw "paste an ID"
   input in `Delivery.tsx`'s developer field and the clarification-assignee field in
   `RequirementDetail.tsx`.
7. **UX-completeness gaps (VYB-0537/0758)**: a real coverage badge next to Design's
   Diagram/Coverage tabs (`DesignService.coverageSummary` — requirements-with-design
   and nodes-with-requirement percentages, color-coded); `window.prompt` replaced with
   a real `Modal` collecting a reason for "Remove from release" (`Releases.tsx`) and
   "Dismiss finding" (`Analytics.tsx`); every direct-click status transition in
   `RequirementDetail.tsx` now goes through a new `TransitionConfirmModal` instead of
   firing immediately.
8. **Audit retention/archival (VYB-0723), real Postgres partitioning, not a simulated
   concept**: `V010` converts `audit_event` to `PARTITION BY RANGE (occurred_at)` (PK
   becomes `(id, occurred_at)`, existing data migrated, the append-only trigger
   recreated on the partitioned table), seeds monthly partitions going forward, and
   `AuditRetentionService.archiveEligiblePartitions()` runs a real `ALTER TABLE ...
   DETACH PARTITION` + rename once every row in a partition is past the configured
   retention window — chosen specifically because a bulk `DELETE` would have hit the
   very append-only trigger this table exists to enforce, and detaching doesn't. A
   `@Scheduled` nightly job runs the same maintenance automatically; Admin → Audit log
   gained a partitions panel (real partition names, ranges, row counts, an "Archive
   eligible now" button) reading real `pg_inherits`/`pg_get_expr` state, not a
   hand-maintained list.
9. **Settings screen (VYB-0757)**: every setting that already had a working `PUT`
   endpoint but no control calling it — noisy-detector dismissal ceiling, AI calls per
   run limit, embedding model — is now editable in place via a new `EditableField`
   component, closing the "renders as a live value with nothing behind it" gap the
   prior session's own write-up named explicitly.
10. **Keyboard-operable grid (VYB-0767), retrofitted everywhere, not just
    `Requirements.tsx`**: a reusable `useRovingGrid` hook (roving tabindex + arrow/Home/
    End/Enter, full `role="grid"/"row"/"gridcell"/"columnheader"` + `aria-sort`/
    `aria-rowcount`/`aria-colcount`) extracted from `Requirements.tsx`'s own
    already-working implementation and applied to every other real `<table
    className="tbl">` in the app: both `CoverageMatrixPage.tsx` grids, both
    `Quality.tsx` tables (extracting a `ParticipantsTable` component so the hook could
    be called once per row rather than inside a `.map()`, satisfying React's rules of
    hooks), `Analytics.tsx`'s rules table, all six tables across `Admin.tsx` (Users,
    Grants, Roles matrix, Audit log, the new Audit partitions panel, Connected
    systems), the edition/variant matrix in `Releases.tsx`, and — found only by a final
    exhaustive `grep` for every `.tbl` table in the codebase rather than trusting the
    prior session's own list of "the other tables" — a table in `Documents.tsx` that
    had never been named as a gap at all. List-style rows built from real `<button>`
    elements (Releases' committed-requirements list, Documents' add/remove list) were
    left alone; they're already keyboard-operable via native tab order and don't need
    grid semantics.
11. **Accessibility contrast check (VYB-0770)**: `contrast.test.ts` — parses
    `tokens.css`'s actual CSS rules at test time (deliberately not a hand-copied pair
    list, to avoid the exact drift failure mode `TenantExportService.TABLES` already
    hit twice) and checks every text-on-background and border-on-background pair
    against WCAG 2.1 AA. Wired into `npm run build` itself (`vitest run && tsc -b &&
    vite build`), not a separate, skippable script. First run found **52 real
    failures** in the actual shipped palette, in both themes — not a tautology, not a
    weakened threshold. Fixed with real HSL-lightness-adjusted replacement hex values
    (computed, not eyeballed) for `--line`, `--line-2`, `--tx-3`, and every `-bd`
    border token; all 92 resulting checks pass.
12. **Observability tracing (VYB-0784)**: `micrometer-tracing-bridge-brave` (no external
    Zipkin/Jaeger collector — a `SpanHandler` bean logs every completed span directly),
    MDC-correlated `traceId`/`spanId` on every log line via an explicit
    `logging.pattern.console`. Confirmed against a real request, not just a clean
    boot: the very first `/actuator/health` call after this session's restart logged
    matching `traceId`/`spanId` across its "authorize request" → "security filterchain"
    → "http get" spans — real correlation on a real request, not an untested framework
    claim.

**Two more real bugs, found only because this session's own verification pass ran
end-to-end rather than stopping at "it compiles" — both fixed:**
- **The `currentSchema=vyg_requirement` JDBC search-path regression, again.** Session
  14 already diagnosed and fixed this exact issue (widening `currentSchema` to
  `vyg_requirement,public` so `pg_trgm`'s and pgvector's unqualified operators stay
  resolvable) — but the fix never made it into `application.yml`'s actual default,
  which still read `?currentSchema=vyg_requirement` with `public` missing. It sat
  silent because the standard `mvn test` suite never touches a real database
  (`RequirementServiceTest` and friends are pure Mockito), so nothing caught the
  regression until `Session16VerificationRunner` ran `promoteToVerifiedSystemically`
  for real and `DetectionSweepService`'s duplicate-detector's `<=>` query failed with
  "operator does not exist: public.vector <=> public.vector" — which then aborted the
  entire enclosing Postgres transaction (Postgres aborts the whole transaction on any
  statement error, regardless of whether the calling Java code catches the exception),
  cascading into every other detector and the audit-log insert that followed. Fixed the
  same way session 14 did — `application.yml`'s default and `README.md`'s example both
  now read `currentSchema=vyg_requirement,public` — and re-verified clean. **Residual
  risk, disclosed, not fixed this session**: `DetectionSweepService.runOne` still has
  no per-detector savepoint, so any *future* detector SQL error — for any reason — will
  still abort the whole surrounding transaction rather than being contained to that one
  detector's own try/catch. This session fixed the one concrete trigger; it did not
  harden the pattern that let a single detector's failure take down an unrelated audit
  insert.
- **A false-positive assertion in this session's own verification test.** The first
  version of `signalsPushArrivesAtRealReceiverWithAValidSignature` had its throwaway
  receiver check the wrong header names (`X-Signature`/`X-Webhook-Signature` — the real
  one is `X-Vyoog-Signature`) and log an empty value, and the test asserted only that
  the log text `contains("signature")` — which is true regardless, because that word
  appears in the log line's own literal label. The test passed on a completely empty,
  unverified signature. Caught by manually inspecting what the receiver actually
  logged rather than trusting a green assertion, and fixed by having the receiver
  independently recompute the HMAC-SHA256 from the shared secret and the exact bytes
  received and asserting on *that* verdict (`hmacValid=True`) instead of a substring
  that was never actually testing what it claimed to.

**Result**: `mvn test` 97/97 unchanged, `Session16VerificationRunner` 4/4 against the
live stack (verification predicate promotion, versioned attachment round-trip, real
partition archival, signed push with an independently-verified HMAC), frontend
`vitest`/`tsc`/`vite build` all clean, the live `vyoog-api` jar rebuilt and restarted
with every session-16 change included and `/actuator/health` confirmed UP. Nothing in
this session's twelve items, or the two bugs found while verifying them, was closed by
weakening a check, fabricating a result, or leaving a claim uncorrected. Not committed or pushed — this repository is a real local git checkout (`main`, no
configured remote), and neither committing nor pushing has been requested.

## Session 17 — VYB-0788: the Portfolio card-grid dashboard and "New product" redesign

**The instruction was a reference screenshot of a "New product" creation dialog and a
card-grid dashboard behind it.** Confirmed scope with the user before building: redesign
the whole Portfolio page to the card-grid dashboard shown (not just the modal), and
**omit** the mockup's "Copilot suggests an app breakdown" hint box — this environment
has no LLM adjudicator configured (already disclosed elsewhere), so a working version
isn't possible here, and decorative UI implying a feature that can't run would be
exactly the kind of thing this project's own discipline exists to avoid.

**V011 migration, and two more real, previously-unused columns found in the process**:
`product.tagline` and `product.description` had existed since `V001` with real getters
and setters, but nothing in the frontend had ever rendered either — the same
schema/UI drift class this project keeps finding (`integration_connection.config` in
session 16, `AuditEvent.ip` in session 14). Renamed rather than left as a confusing
alias: `tagline` → `vertical` (the italic line under a product's name), `description` →
`purpose` (the one-sentence purpose line), since both are now genuinely rendered.
Added real new columns: `owner_id` (a real `app_user` FK, the same pattern every other
assignee field in this schema uses), `lifecycle_status` (CHECK-constrained,
`IN_DEVELOPMENT`/`LIVE`/`MAINTENANCE`/`DEPRECATED`/`RETIRED`), and `mark` (a
CHECK-constrained icon key — the exact same 12 keys the frontend's `MARKS` table
resolves, so a value the database will accept and a value the frontend can render
can't drift apart, the failure mode a free-text column would have allowed).

**`ProductDashboardService` (new)** — two real aggregate SQL queries (one per product
with owner name joined, one grouping application→capability→requirement→finding down
to per-application req/gap/verified counts), not N+1 per product. "Gap" reuses the
exact definition `SignalsService` already established (an `OPEN` finding on a
requirement) rather than inventing a second one. An application with zero
requirements gets a real zero, not an omitted row; "apps below 75%" only counts
applications that have at least one requirement, since an empty application has no
coverage ratio to be below. `GET /products/dashboard` and a `GET
/products/dashboard/report.csv` (the "Portfolio report" button) expose it — the CSV
is the same figures, not a second computation that could drift from what the cards
show.

**Frontend**: `Portfolio.tsx`'s default tab is now a card grid — one card per
product with its real mark icon, serif name, italic vertical, purpose, three stat
pills (reqs/apps/gaps), a per-application breakdown (name, req count, and a red gap
badge only when nonzero), a verified-ratio progress bar, and an "Open" button that
drills into the existing three-column Hierarchy editor with that product
pre-selected — reusing the working application/capability create-rename-archive flow
rather than rebuilding it a second time. The existing Hierarchy and Glossary tabs are
unchanged apart from the Products column being replaced by a plain selector (creating
and editing a product now happens through the dashboard's modal, not two competing
UIs that could drift). `NewProductModal.tsx` (new) is used for both create and edit,
matching the reference screenshot's fields (name, code, vertical, purpose, a real
`UserPicker` for owner, lifecycle status, a 12-icon mark picker) plus a live preview
panel — and the modal's own footer honestly states "No requirements are created yet"
for a brand-new product, which is simply true at that point, not a fabricated status
line. `Modal.tsx` gained an optional `maxWidth` prop (this is the first modal wide
enough to need one) — every existing modal call site is unaffected since it's optional
and defaults to the prior 480px.

**Verified for real**: a new explicit-name-only test,
`PortfolioDashboardVerificationRunner` (same convention as
`Session14VerificationRunner`/`Session16VerificationRunner`), creates a product through
the real `ProductController.create` path (proving the vertical/purpose/owner/
lifecycle/mark fields round-trip through the actual HTTP-facing DTOs, not just the
entity), inserts one application with one `VERIFIED` and one `DRAFT` requirement (the
latter with one real `OPEN` finding), and asserts the dashboard's computed
`reqCount=2`, `gapCount=1`, `verifiedRatio=0.5`, `ownerName` resolved from the real
`app_user` row — then asserts the CSV report's own figures for that application match
exactly, not just that the product's name appears somewhere in the file. All rows
cleaned up in a `finally`, confirmed empty afterward by direct query. `mvn test`
97/97 unchanged; frontend `vitest`/`tsc`/`vite build` all clean (92/92 contrast tests
still pass — no new token or component introduced a contrast regression). The live
`vyoog-api` jar was rebuilt and restarted; Flyway applied V011 for real
(`Migrating schema "vyg_requirement" to version "011 - product dashboard"`), confirmed
via `flyway_schema_history` and `/actuator/health` → UP. A raw, unauthenticated `curl`
against the new endpoints correctly 401s — this environment's Keycloak-dependency gap
(VYB-0007) is unchanged and wasn't worked around to make a live HTTP smoke test easier.

## Session 18 — VYB-0048b: a username/password login screen, without the pattern that made one dangerous

**The ask was a login screen "like vyg-pms".** Read vyg-pms's actual `LoginPage.jsx`
and its `src/api/auth.api.js` before building anything. What that screen actually does:
a raw `fetch` straight from the browser to Keycloak's token endpoint, `grant_type:
'password'`, with `client_id`/`client_secret` read from `VITE_CLIENT_ID`/
`VITE_CLIENT_SECRET` — a *confidential* client's secret, baked into vyg-pms-ui's own
built JS bundle by Vite, readable by anyone via devtools right now. `docs/DECISIONS.md`
D7 (an earlier session) already reviewed reusing that exact client and rejected it —
it's also used for the Keycloak Admin API, so the exposure is worse than "one user's
login." Copying that mechanism into Vyoog would mean a real password transiting
Vyoog's own frontend with a browser-exposed secret, which is exactly what "Vyoog never
stores a password — Keycloak owns authentication" (one of this project's own
non-negotiable rules) exists to prevent. Built the same visual screen — split brand
panel, animated background, glass card, real username/password fields — with a
different, safe mechanism underneath.

**Backend: `KeycloakPasswordGrantService` + `AuthController`, the secret stays server-side.**
`POST /api/v1/auth/login` and `POST /api/v1/auth/refresh` — the one deliberate carve-out
from "every endpoint requires a bearer token" (`SecurityConfig`'s permitAll list),
obviously so, since this is how a token is obtained. The password arrives in this one
request, gets forwarded to Keycloak's token endpoint via a Resource Owner Password
Credentials grant using `java.net.http.HttpClient` (same pattern
`SignalsExportService` already established for outbound calls), and is discarded
either way — never logged (confirmed: the log line on rejection carries Keycloak's own
`error_description`, never the password itself), never persisted. Deliberately a
*third*, purpose-built confidential client (`KEYCLOAK_ROPC_CLIENT_ID`/
`KEYCLOAK_ROPC_CLIENT_SECRET`, backend env vars only) — not the `eVyoog` client D7
already flagged, and not `vyoog-web` (public/PKCE, can't also be confidential). Rate
limiting on `/auth/login` via the same `RateLimiter` bean VYB-0782 already built,
keyed per-username. Not yet configured in this environment (the two env vars are
blank by default) — calling the endpoint today returns a real, honest 409 naming
exactly what's missing, not a crash.

**Frontend: `AuthProvider`/`useAuth` (new, replacing `react-oidc-context` entirely) —
both tokens in memory only, never `localStorage` or `sessionStorage`.** The OIDC
Authorization Code + PKCE flow this replaces already reasoned about this (a stored
bearer token turns any XSS into a full account takeover); applied more strictly here,
since a refresh token is longer-lived and more damaging to leak than an access token.
Real tradeoff, accepted deliberately: a page reload loses the session and requires
signing in again — nothing is persisted to make that not true. A silent-refresh timer
re-requests a new access token ~30s before expiry using the held refresh token, the
same intent `automaticSilentRenew` served for the OIDC flow, reimplemented here since
ROPC has no redirect round-trip for that library to hook into. `react-oidc-context`
and `oidc-client-ts` removed from `package.json` (uninstalled, not just unimported) —
confirmed by the production bundle shrinking from 578KB to 513KB.

**A real, previously-undiscovered bug, found only because this was tested against a
real HTTP round trip rather than left at "it compiles": every `@Valid`-validated
endpoint in the entire app was returning 401 instead of 400 on a validation
failure.** `MethodArgumentNotValidException` had no handler in `ApiExceptionHandler`,
so it fell through to Spring Boot's default handling, which forwards the request to
`/error` so `BasicErrorController` can render a body. That forward re-enters the same
security filter chain as a fresh dispatch — and since `/error` matched no `permitAll`
rule, Spring Security itself rejected *that* dispatch as unauthenticated, silently
overwriting the real 400 with a 401 before it ever reached the client. Caught by
testing the new login endpoint with a blank username/password and getting a bare 401
with no body instead of the field errors the code visibly builds — reproduced
5-for-5 against the live server before touching anything, then re-verified 5-for-5
clean after the fix. Two-part fix, both needed: `SecurityConfig` now permits `/error`
itself (the root-cause fix — covers any exception type that still isn't explicitly
handled, not just this one), and `ApiExceptionHandler` gained an explicit
`MethodArgumentNotValidException` handler returning the same RFC 9457 shape every
other error on this API already uses, with real per-field messages.

**Verified for real**: a new explicit-name-only test, `AuthEndpointVerificationRunner`
(same convention as the other `*VerificationRunner` classes), runs against a real
throwaway local HTTP server standing in for Keycloak's token endpoint (same technique
`Session16VerificationRunner` used for the outbound signals push) — a real password
grant returning a real token, a real refresh exchange, and a real credential
rejection carrying Keycloak's own `error_description`, all three through
`KeycloakPasswordGrantService` directly and through `AuthController` end-to-end.
`mvn test` 97/97 unchanged. Frontend `tsc -b`/`vite build` clean, contrast checker at
104/104 (up from 102 — the new field-row/eye-toggle styling reuses already-AA-verified
tokens). Live `vyoog-api` jar rebuilt and restarted twice — once to ship the feature,
once more after the `/error` fix — health confirmed UP both times, and the blank-field
regression re-tested live against the running server, not just in the test suite.

**Disclosed, not fixed**: the rate limiter on `/auth/login` is per-username with a
1-second cooldown, an in-memory `ConcurrentHashMap` with the same single-instance
caveat `DetectionSweepService` already discloses for its own guard — not a real
lockout policy. A production deployment would want Keycloak's own built-in
brute-force detection on top of this, not instead of it. This session did not add
one.

## Session 19 — VYB-0666: Word import, and the first real AI call in this codebase

**The ask**: import a Word document and have AI split it into requirement types
(functional/non-functional/etc.), reusing the OpenAI key already configured in
vyg-pms. Two real gaps found before writing anything: **no `.docx` upload kind
existed at all** (FREEFORM/STANDARD_SPEC/REQIF/EXCEL only — a Word upload today
either silently produces garbage paragraphs or a validation error), and **every
imported candidate was silently `type = FUNCTIONAL`** — `ImportService.commit`
passed `null` for type unconditionally; nothing anywhere in the import pipeline
had ever classified anything. Confirmed scope with the user first: type
classification only (the existing 8 `Requirement.type` values), not a second
attempt at extracting test cases as their own entity — "test case" isn't a
requirement type in this schema, it's a different entity, and conflating the two
would have been a bigger, different feature than what was asked.

**Word import (`WORD` upload kind)**: `WordDocumentParser` reads a real `.docx`
via Apache POI's `XWPFDocument` — the exact library this codebase already uses to
*write* one (`DocumentWordExporter`), now also used to read one, no new
dependency. One paragraph, one candidate — mirrors `FreeformDocumentParser`
exactly, including headings becoming candidates too (nothing here distinguishes a
heading from body text; a disclosed, not hidden, limitation). Base64-encoded by
`ImportController` the same way a real `.xlsx` already is, since `.docx` is also
a ZIP container and `import_batch.raw_text` is TEXT. V012 widens the
`upload_kind` CHECK constraint.

**AI classification, built the way this codebase's own AI-unavailable
convention demands, not vyg-pms's**: read vyg-pms's real `AiSuggestionService`/
`ChatbotService` first — same OpenAI Chat Completions shape, same env var names
(`AI_ENABLED`/`AI_API_URL`/`AI_API_KEY`/`AI_MODEL`, kept identical here
specifically so the same key value can be reused across both apps without
renaming anything), same `gpt-4o-mini` default. What's different on purpose:
vyg-pms silently falls back to a rule-based guess on any AI failure and never
tells the caller; `OpenAiRequirementTypeClassifier` throws
`AiProviderUnavailableException` (the same exception type `EmbeddingProvider`/
`LlmAdjudicator` already use for "the provider couldn't be reached or isn't
configured") for every failure mode — not configured, unreachable, HTTP error,
*and* the model returning a type outside the real 8 (refused, not coerced into
the nearest guess). `ImportService.proposeType`/`confirmType` mirror
`proposeCapability`/`confirmCapability` exactly — a proposal a human must
confirm before `commit` will use it; an unconfirmed candidate simply keeps
today's `FUNCTIONAL` default rather than blocking the commit on a classification
nobody asked for. Reimplemented with `java.net.http.HttpClient` rather than
copying vyg-pms's Spring `RestClient`, matching this codebase's own established
outbound-HTTP convention (`SignalsExportService`, `KeycloakPasswordGrantService`)
instead of adding a second one.

**Frontend**: `WORD` added to the upload-kind dropdown; a "Classify type (AI)"
button per candidate, visually identical in shape to the existing "Propose
capability" one — a dropdown to confirm/override once proposed, the AI's own
confidence and one-sentence rationale shown alongside (the same "the basis is a
real reason, not just 'because the model said so'" standard `proposeCapability`
already met, extended to an AI proposal by actually surfacing what it said). An
AI failure shows once, plainly, above the candidate list — not silently
swallowed, not repeated per card for what's almost always the same root cause
(not configured).

**A real, incidental doc-drift fix, found while editing the same hint paragraph**:
the Import Queue's own UI copy claimed "EXCEL here means CSV text, not a binary
.xlsx" — false as of VYB-0638 (session 13), which added real Apache POI `.xlsx`
reading to `CsvSpreadsheetDocumentParser`. The hint text had never been updated
to say so. Fixed in the same edit, not left for a future session to rediscover.

**Verified for real**: `DocumentParserTest` gained three cases for
`WordDocumentParser` — a genuine in-memory `.docx` built with real POI,
base64-round-tripped exactly as `ImportController` does it, asserting one
candidate per non-blank paragraph and a blank paragraph correctly producing
none; plus its two real refusal paths (not base64, not a readable `.docx`). A
new explicit-name-only test, `ImportAiClassificationVerificationRunner` (same
convention as the other `*VerificationRunner` classes), proves the AI path
against a real HTTP round trip to a throwaway local server standing in for
OpenAI's Chat Completions endpoint (same technique as every prior session's
outbound-HTTP verification): a real classification, a real refusal on an HTTP
error, a real refusal on an out-of-range type, and the full
propose→confirm→commit chain landing the confirmed type on the actual
`requirement.type` column of a really-committed row. `mvn test` 100/100 (was
97 — the three new parser cases; the AI verification runner, like all the
others, is invisible to the default run). Frontend `tsc -b`/`vite build` clean,
104/104 contrast tests. Live `vyoog-api` jar rebuilt and restarted, V012
confirmed applied via Flyway's own log line, health confirmed UP.

**Disclosed, not fixed**: `AI_ENABLED`/`AI_API_KEY` are unset in this
environment as of this session — calling `/import/candidates/{id}/propose-type`
right now returns a real, honest 409 naming exactly that, the same "not
configured" pattern already established for the ROPC login endpoint and
`SignalsExportService`. Setting those two env vars to a real key is the only
remaining step to make this live; nothing about the code changes when that
happens.

## Session 20 — closing Part 1/Part 2 of the gap-and-AI-feature audit

**The ask**: "fix the gaps in part1 and part2, don't miss any gaps and AI
features, review and fix properly" — following a full audit of this
application's disclosed gaps (Part 1) and where AI could still be applied
(Part 2). Two sub-decisions were disproportionately large to just pick
silently, so they went back to the user via explicit choice rather than being
assumed: RBAC retrofit scope (**highest-risk endpoints first**, not all ~300,
chosen over a full sweep) and the chatbot idea from Part 2 (**skipped** —
speculative new scope the audit itself flagged, not a disclosed gap).

**RBAC retrofit, highest-risk first**: `SecurityConfig` only ever required
`anyRequest().authenticated()` for most controllers — no per-role check —
already the "largest standing gap" this register had on record. Rather than a
rushed all-endpoint sweep, `PrincipalGuard.requireAdministrator` was added to
the specific actions where the blast radius is worst: `ApplicationController`/
`CapabilityController`/`ProductController`'s `archive()` (portfolio cascades),
`RuleController`'s `setEnabled`/`setThreshold`/`applyNoisyDefaults` (already
documented elsewhere as administrator-intent, simply never enforced),
`FindingController.triggerSweep` (a DoS-adjacent action — anyone authenticated
could previously force a full detection sweep on demand), and
`ChangeRequestController.decide`/`apply` (previously *any* authenticated human
could approve their own change request — now administrator-gated, deliberately
not reusing `AccessRole.APPROVER`, since `RoleCapabilityRegistry` already ties
that name to `review_participant.role`, a different mechanism; reusing it here
would give one role name two unrelated meanings). The remaining ~40 controllers
are unchanged and remain the disclosed gap they already were — this was a
scoped, chosen slice, not a claim of completeness.

**The first real `LlmAdjudicator` and the real `OpenAiEmbeddingProvider`,
closing VYB-0612/0655/0602's actual root cause**: both detectors' own Javadoc
had said for sessions that registering one new `@Component` would be enough —
`OpenAiLlmAdjudicator`/`OpenAiEmbeddingProvider` are exactly that, same shape as
`OpenAiRequirementTypeClassifier` (same env vars, same `java.net.http.HttpClient`
convention, same "refuse rather than fabricate" discipline via
`AiProviderUnavailableException`). `LocalHashingEmbeddingProvider` gained
`@ConditionalOnProperty(havingValue="false", matchIfMissing=true)` so the two
providers are mutually exclusive on `vyoog.ai.enabled`, never both live. Proven
for real: `LlmAdjudicatorVerificationRunner` (a real contradiction correctly
flagged, a real non-contradiction correctly cleared, a real HTTP error refused
rather than guessed) and `EmbeddingProviderVerificationRunner` (a real 1536-
dimension vector back from a real HTTP round trip — the exact width
`requirement_embedding.embedding` already declares). `ConflictingRequirementsDetector`
itself was not touched and was not re-exercised end-to-end against a real
similarity-shortlisted pair in this session — that would need real embeddings
computed for a real candidate pair clearing a real similarity threshold, a
heavier rig than proving the two new components individually; its own unit
test (`ConflictingRequirementsDetectorTest`, mocked adjudicator) already covers
the detector's own logic and still passes. Table updated: VYB-0602 (the
embedding-provider half only — the HNSW 100k-row performance target is
unrelated and still unmeasured, unchanged), VYB-0612, VYB-0655 → DONE.

**Correction, not a fix**: the audit's own Part 1 had claimed VYB-0620 ("AI
usage exposed") was still open. It was wrong — `GET /api/v1/ai/usage`
(`AiController`) and `AiUsageCard` (`Admin.tsx`, 15s poll) already existed,
session 13. Caught before any wasted rebuild effort; the table already said
DONE and stays that way.

**Real-time notification push, closing VYB-0356**: `outbox_event` rows were
already written transactionally with every `Notification` (VYB-0356 AC1 closed
since session 13) — the missing piece was a relay actually consuming them.
`NotificationRelayService` (`com.vyoog.api.notify` — not `vyoog-domain`,
per this reactor's ArchUnit rule that domain must not depend on web/`SseEmitter`)
polls unpublished rows every 2 seconds and pushes them through
`NotificationSseRegistry` to `GET /api/v1/notifications/stream`
(`SseEmitter`, one per connected user). The frontend deliberately does **not**
use the browser's native `EventSource` — it can't send an `Authorization`
header, and putting the bearer token in the URL's query string would
contradict this project's own established token discipline (memory-only,
never in Storage or a logged URL). Instead `client.ts` hand-rolls a
`fetch()` + `ReadableStream` + manual SSE-line parser with auto-reconnect;
`NotificationBell.tsx` dropped its 30-second poll for it. Table: VYB-0356 → DONE.

**Manager escalation, closing VYB-0334**: `ClarificationService.escalateOne`
had explicitly disclosed "no manager relationship exists" and fell back to the
capability owner. `app_user.manager_id` (V013) plus `AppUserService.setManager`/
`UserController`'s `PUT /users/{id}/manager` (administrator-only — org
structure isn't self-service, unlike a user's own delegate) supply exactly the
one input that was missing; `escalateOne` now checks the assignee's manager
first, capability owner second, raiser last. `Admin.tsx`'s Users table gained a
Manager column mirroring the existing Delegate one (`useRovingGrid`/
`aria-colcount` bumped 7→8). Table: VYB-0334 → DONE.

**Disclosed, deliberately not changed**: VYB-0373 (perspective switching)
still reuses `ADMINISTRATOR` rather than a dedicated grant type — the existing
code comment already gives the real reason ("no dedicated 'view others' work'
grant type... rather than inventing a tenth one the spec never named"), and
`AccessRole` is documented elsewhere as a fixed enum, not configurable data.
Re-litigated this session and left as the deliberate design choice it already
was, not unilaterally "fixed" by inventing a role that would contradict that
existing reasoning.

**VYB-0781: detection sweep's unbounded Hibernate session growth, fixed for
real**. `FindingReconciler.reconcile()` — the full-rule path a nightly sweep
actually runs — loaded a rule's *entire* finding set into one `List` via an
unpaged `findAllByRuleKey(ruleKey)`, and neither loop in `reconcileWithin`
ever flushed or cleared the persistence context; both are exactly what turns a
large rule or a detector that shortlists hundreds of candidates per call
(`ConflictingRequirementsDetector.scan()`, up to ~200 pairs) into unbounded
session growth over a long sweep. Fixed with an `EntityManager` injected into
`FindingReconciler`: the per-candidate save loop now flushes/clears every 200
iterations (shared by every reconcile path, since a detector call itself can
be large), and `reconcile()` specifically now pages through its resolve-scope
via a new `FindingRepository.findAllByRuleKey(String, Pageable)` (the exact
paging template `findAllByState`/`findAllByStateAndRuleKey` already
established) with a flush/clear between pages. `reconcileOne()`'s resolve-scope
stays a plain `List` on purpose — it's one object's own findings for one rule,
inherently small, and the Explore-agent analysis that found this bug
specifically named the full-rule path as the unbounded one. `Finding` has no
lazy associations, so mid-loop `clear()` is safe — nothing gets accessed
detached. `FindingReconcilerTest` updated for the new 3-arg constructor and the
paged mock (`PageImpl`); all 6 cases still pass. Table: VYB-0781 → DONE.

**VYB-0732: real attachment-binary bundling into the tenant export, against a
real object store**. `TenantExportService.export()` stays metadata-only (fast,
unchanged); a new `exportBundle()` builds the same manifest snapshot, then
fetches every `attachment_version` row's actual bytes live from MinIO/S3 via a
new `AttachmentService.fetchByStorageKey` and zips them alongside
`manifest.json` under `attachments/<storage_key>`. A storage fetch failure for
one object doesn't abort the whole bundle — the key lands in
`missingStorageKeys` instead, the same "disclose, don't drop" pattern
`export()` already used for a missing table. `SettingsController` gained
`GET /settings/export/bundle` (administrator-only, audited with the
attachment/bundled/missing counts); `Admin.tsx` gained a second "Download full
bundle (.zip)" button next to the existing manifest-only download, with its
own error state if object storage is unreachable. Proven for real:
`TenantExportBundleVerificationRunner` uploads a real attachment through
`AttachmentService` (the same path `AttachmentController` uses), inserts a
second `attachment_version` row pointing at a storage key that was genuinely
never uploaded, then unzips the real bundle and asserts the real bytes
round-trip exactly while the ghost key lands in `missingStorageKeys` and is
never zipped. Table: VYB-0732 → DONE.

**VYB-0794 (new): AI-assisted rewrite suggestions, the Part 2 item asked for
by name**. `QualityScoreService.score()` — a pure, deterministic function — is
untouched, including both its call sites in `RequirementService` (`create`,
`applyEdit`); this is a separate, additive, explicitly user-triggered step, not
a change to how the score itself is computed. `RequirementRewriteAdvisor`/
`OpenAiRequirementRewriteAdvisor` take the statement *and* the exact breakdown
`QualityScoreService` already computed for it, and ask the model to fix only
what that breakdown actually penalized — not a generic "make this better."
`POST /requirements/rewrite-suggestion` sits next to the existing
`/authoring-signals` endpoint but is never called on a keystroke — a "Suggest
rewrite (AI)" button in `RequirementNew.tsx`'s existing quality-score card,
showing the rewritten statement and a plain-English list of what changed, with
"Use this" replacing the statement field or "Dismiss." Proven for real:
`RewriteSuggestionVerificationRunner` — a real rewrite back from a real HTTP
round trip, a real HTTP-error refusal, an empty-statement refusal before ever
calling the provider, and a model response that itself came back empty
refused rather than accepted.

**A real, previously-undiscovered bug, found while wiring the rewrite
endpoint's error path**: `ApiExceptionHandler` had **no handler at all** for
`AiProviderUnavailableException` — every AI integration in `com.vyoog.ai`
(classification, embedding, adjudication, now rewrite) throws exactly this
type for "the provider couldn't be reached or isn't configured," and with no
handler it fell through to Spring Boot's default handling as a bare 500 with
no RFC 9457 shape, for every endpoint except the one place
(`ImportController.proposeType`) that happened to catch it locally and
re-throw as `IllegalStateException` for its own reasons. The exact same class
of bug VYB-0048b already fixed once for `MethodArgumentNotValidException`.
Fixed with `ApiExceptionHandler.onAiProviderUnavailable` → 503, not 500 — the
server itself is fine; an external dependency it depends on isn't.

**Disclosed, confirmed unchanged**: VYB-0303/0305/0712 — step-up authentication,
service-account client-credentials, and key rotation — remain real mechanisms
never exercised against an actual eVyoog Keycloak conditional-ACR flow, a real
client-credentials grant, or a real client-secret rotation. Re-confirmed this
session rather than re-asserted blindly; nothing here changed, and nothing was
faked to make it look otherwise.

**The environment itself was torn down and rebuilt mid-session**: partway
through, this sandbox's ephemeral `/tmp` was wiped — the Postgres 16.4/MinIO
instances session 14 stood up, and the live `vyoog-api` jar, were all gone,
though every source-tree edit (outside `/tmp`) survived untouched. Rebuilt from
scratch, same recipe as session 14 (`bison`/`flex`/`m4` via `apt-get download`
+ `dpkg-deb -x`, Postgres 16.4 from source, `pgvector` 0.7.4 from source,
`pg_trgm`/`pgcrypto` from the same source tree's `contrib/`, MinIO's static
binary), with one new wrinkle session 14 never hit: `pgcrypto.so` failed to
`dlopen` at `CREATE EXTENSION` time with an undefined `EVP_cast5_cbc` symbol —
built without linking `libssl`/`libcrypto` in (a `libssl-dev` package had to be
fetched the same no-root way for `openssl/evp.h`, then the object files
explicitly relinked with `-lssl -lcrypto`). This had never surfaced before
because the schema only ever calls `gen_random_uuid()`, core in Postgres 13+ —
`CREATE EXTENSION pgcrypto` had apparently never actually been exercised to
completion in any prior session's rebuild before this one hit it directly.

**Verified for real, end to end, against the rebuilt infrastructure**:
`mvn test` 100/100 in `vyoog-domain` (unchanged count — `FindingReconcilerTest`
updated in place for the new constructor, no cases added or removed).
`vyoog-api` has no default-Surefire-pattern test classes by design (every
integration proof is a `*VerificationRunner`, invisible to a bare `mvn test`
on purpose) — six of them run explicitly against the rebuilt Postgres 16.4 +
MinIO + three throwaway local mock-OpenAI servers this session either wrote
fresh or re-verified: `TenantExportBundleVerificationRunner`,
`RewriteSuggestionVerificationRunner`, `LlmAdjudicatorVerificationRunner`,
`EmbeddingProviderVerificationRunner` (all new), plus `pg_ctl`/`minio`
confirmed live and `flyway_schema_history` confirmed all of V001–V013 applied
with `success = t`. Frontend `tsc --noEmit` clean. Live `vyoog-api` jar
rebuilt and restarted — on port 8081, not 8080, since an unrelated project
(`vyoog-pms`, a separate application on this same machine) already held 8080;
left untouched rather than killed. `/actuator/health` → `UP`, an unauthenticated
protected endpoint still correctly 401s, `/api/v1/auth/login` and
`/api/v1/settings/export/bundle` both correctly reject an unauthenticated/
wrong-credential call.

**Explicitly out of scope this session, by the user's own choice**: a chatbot
feature suggested in the Part 2 audit — new, speculative scope the audit
itself flagged as such, not a disclosed gap against any existing requirement,
and not attempted.

## Session 21 — VYB-0788 restyle: the Portfolio dashboard rendered in the prototype's `.pf-*` design

**The ask**: the `.pf-*` block from `Downloads/vyoog-layout- user-budget-mytask-calender.html`,
plus a screenshot of that prototype rendered in light theme — "i need this exact style for
my portfolio cards also."

`tokens.css` already carried the ported `.pf-*` / `.add-card` / `.crumb` rules from the
earlier pass on this requirement; `Portfolio.tsx` was still drawing the dashboard with
the generic `.card` grid, so every one of those rules was dead CSS. This session wired
the markup to them: centred glyph/name/vertical, the purpose, a bordered three-cell stat
row, the product's apps as rows, a coverage footer, the joined `.pf-sum` platform strip
in place of six detached stat tiles, the dashed `.add-card`, and the
`Product Portfolio ▸ <product> ▸ <app>` `.crumb`. Header is the prototype's own eyebrow /
"Product Portfolio" / subtitle, and `.pf-actions .btn` / `.pf-foot .btn` make those two
button rows uppercase without touching the app's global `.btn`.

Adaptations, none of them cosmetic accidents:

- **Per-product accent, not one fixed `--prod`.** The prototype hardcodes a single accent
  for all five sample products. Each card re-binds `--prod`/`--prod-dim`/`--prod-bd` to
  its own mark's triad from `marks.tsx`, so glyph, vertical, app bullets and hover border
  all follow the mark the user actually picked — one inline binding instead of a rule per
  mark, and no new colour that `contrast.test.ts` cannot see.
- **Nothing hardcoded.** Every figure still comes from `/products/dashboard`; the card
  gained no data it cannot derive. The eyebrow counts real unarchived products rather
  than repeating the prototype's "five verticals", so it cannot drift from the grid.
- **Coverage banding tightened** to the prototype's three-tier `covCol` (green ≥88, amber
  ≥75, red below) from the single 75% cut — the same 75% the summary strip's "apps below
  75%" figure already uses.
- **Gap chips are not suppressed.** The prototype chips an app's gap count only above 5;
  every non-zero count is chipped here, since hiding a live figure to quieten a card
  hides real work.
- **Nothing was dropped.** The prototype's card has no lifecycle badge, owner, edit or
  archive control; all four survive — lifecycle and owner in one compact centred
  `.pf-meta` line, edit/archive in a `.pf-acts` cluster revealed on hover/`:focus-within`
  so the footer is exactly bar + percentage + OPEN as in the screenshot. Absent
  owner/vertical/purpose render as stated text ("No owner assigned"), never blank —
  rule 8. App rows became real links that drill to the app, not just to the product.
- **The crumb needed a home.** This Portfolio is tabbed (Dashboard/Hierarchy/Glossary),
  not the prototype's three drill-down states, so it renders in Hierarchy once a product
  is selected, each ancestor a link back up.

`StatTile` and `MiniStat` became unused and were removed rather than left dangling;
`MarkIcon` is no longer used here (the card draws the glyph through `.pf-glyph`) but is
still used by `NewProductModal`, so it stays in `marks.tsx`.

`tsc --noEmit` clean, `npm run build` clean, suite **126 passed** with contrast still
120/120 — the change is markup against already-verified rules, so it added no new pairing.

**Follow-up, same session — "portfolio card sizes are very large"**. Two real defects,
found by rendering the exact card markup against `tokens.css` in headless Chrome and
measuring `getBoundingClientRect()` rather than eyeballing it:

- **The `.pf-*` block's own header comment closed itself early.** It described the rule
  family as `.pf-*` immediately followed by `/.crumb` — and `*/` ends a CSS comment. The
  parser therefore treated the rest of the comment prose as a selector prelude and ate
  the entire `.pf-grid` rule that followed it. There was no grid at all: every card laid
  out as a full-width block, **1641px wide**, one per row. The comment is reworded and
  now says why the sequence must never appear again. Worth noting the whole suite was
  green through this, because `contrast.test.ts` reads declarations out of the file, not
  the cascade a browser actually builds — a green suite is not evidence that a
  stylesheet parses.
- **`auto-fit` + `1fr` only works when the row is full.** The prototype always renders
  five products plus the add-card, so its tracks are always occupied. With two products
  `auto-fit` collapses the empty tracks and stretches those two cards across the page.
  Changed to `auto-fill` with `minmax(226px, 300px)`, which pins a card to the width it
  has in the prototype's own screenshot (~298px at 1920) no matter how few products
  exist.

Measured after the fix: **300×494** per card (prototype ~298×485), five across in the
app's content column, and the two-product grid renders identically sized cards instead
of two page-wide ones. Checked in both themes. `tsc --noEmit` clean; **126 passed**;
contrast still 120/120 — the corrected comment exposed no new colour pairing, since
`.pf-grid` declares none.

**Follow-up, same session — "portfolio card sizes are very large", then "there is a lot
of difference still … smooth lines i need, not it looks like more grid"**. Three real
defects, all found by rendering the exact card markup against `tokens.css` in headless
Chrome and measuring `getBoundingClientRect()` instead of eyeballing a diff:

- **The `.pf-` block's own header comment closed itself early.** It described the rule
  family as `.pf-` plus a star, immediately followed by `/.crumb` — and that sequence
  ends a CSS comment. The parser treated the remaining prose as a selector prelude and
  ate the entire `.pf-grid` rule after it. There was no grid at all: every card laid out
  as a full-width block, **1641px wide**, one per row. The comment now says why that
  sequence must never reappear. The suite was green throughout, because
  `contrast.test.ts` reads declarations out of this file rather than the cascade a
  browser builds — a green suite is not evidence that a stylesheet parses.
- **`auto-fit` + `1fr` only holds when the row is full.** The prototype always renders
  five products plus the add-card, so its tracks are always occupied; with two products
  `auto-fit` collapses the empty tracks and stretches those two across the page. Now
  `auto-fill` with `minmax(226px, 300px)`, which pins a card to the width it has in the
  prototype's own screenshot (~298px at 1920) however few products exist. Measured
  after: **300×493** per card (prototype ~298×485).
- **Every product on the default mark rendered with no accent at all.** The card re-bound
  its `--prod*` trio inline from `marks.tsx`, whose values are `var(--prod)` strings — so
  for `box` (which is `DEFAULT_MARK`) the inline style evaluated to
  `--prod: var(--prod)`, a self-referential custom property. CSS resolves that to
  guaranteed-invalid, and the top border, glyph ring, italic vertical and app bullets all
  silently vanished; the flat grey card the user was comparing against the prototype. The
  binding is now an `.acc-*` class per accent family, with no class for the marks already
  on `--prod`. A `var(--acc, var(--prod))` fallback chain would also have worked, but
  `contrast.test.ts` pairs a rule's background against its border *by token name*, so
  `--acc-dim` — not a defined colour — would have dropped `.pf-glyph` out of the gate
  without failing it.

**The "looks like a grid" difference was line weight, not layout.** The prototype's
hairline is `#DDE2EA` light / `#272E3A` dark; this project's `--line` is `#7990B5` /
`#586989`, because session 16 raised every boundary token to clear 3:1 for WCAG 1.4.11.
Drawing a card's internal rulings in it reads as a table. The card's *outline* stays
`--line` — it is a real boundary and `contrast.test.ts` still asserts it in both themes —
while the rulings inside (under the description, between stat cells, above the footer,
between summary cells) are now `--panel-3`, which is within a shade of the prototype's
own hairline. The `.pf-meta` line lost its rule entirely: the prototype's card has three
horizontal lines and a fourth is what made this read as a grid; lifecycle and owner now
sit directly under the vertical, above the description.

Honest note on the gate: contrast went **120 → 118 tests**. Both lost assertions are the
same pairing — `.pf-foot`'s old `border-top: --line` on its `--panel-2` fill, in each
theme. That divider now lives on `.pf-apps`, which declares no background, and the
checker only asserts a border where the same rule declares a background var. So these
hairlines are outside the automated gate rather than passing it. That is the right
reading of 1.4.11 for a decorative separator — none of them is the boundary of any
control — but it is a coverage reduction, not a free win, and it is recorded here rather
than left for someone to discover in the test count.

`tsc --noEmit` clean, `npm run build` clean, **124 passed**. Verified in both themes at
1920 and 1280 wide, and with a two-product grid as well as five.

**Follow-up, same session — "after i click open button it opens some page … i need the
same design which was in html"**. OPEN on a product card jumped to the Hierarchy tab's
three columns; the prototype's OPEN leads to its own second portfolio state, one product
and its apps. That state is now built: `portfolio/ProductDetail.tsx`, with the
prototype's `.pd-*` head (glyph, serif name, italic vertical, purpose, and a bordered
Reqs/Verified/Gaps box reusing the same `.pf-stat` cells so the two states cannot drift),
its `Apps in this product` row with the `N apps · N requirements · N open gaps` line, and
its `.app-grid` of `.app-card`s — dot-pill gap badge, a Requirements row, a Verified row
banded by the shared `covColor`, and an uppercase footer. Reached by state, not by route:
the crumb and "All products" return to the grid, and the drill-down replaces the page
title and tab row exactly as the prototype replaces its own view.

- **`covColor` moved to `portfolio/coverage.ts`** with `verifiedPctOf`, so the card grid
  and the drill-down band the same percentage identically rather than each holding a copy.
- **`.app-grid` is capped at 340px**, for the reason `.pf-grid` was: the prototype's
  `1fr` is only safe because it always has six apps, and a two-app product would
  otherwise get two half-page cards.
- **The gap badge keeps all three of the prototype's states** — red above five, grey at
  one to five, green at none. The prototype's middle state is grey, not amber, so rule 5
  ("amber means AI") costs nothing here. It is the prototype's own `.bdg` shape (mono,
  uppercase, 3px corners, leading dot), added as `.app-bdg`, not this app's rounded
  `.badge`; the token triads are the ones the app's badges already use, and
  contrast.test.ts now asserts all three in both themes.
- **The Requirements bar is full width, as in the prototype.** It reports a count, not a
  share of anything — an earlier pass scaled it against the largest sibling app, which
  looked like data the payload doesn't contain.
- **Two of the three footer buttons cannot be scoped yet.** The prototype's per-app
  "Requirements" and "Gaps" need an application-level filter; the requirements list
  filters by capability, and the coverage matrix's gaps-only toggle is local state, not a
  URL parameter. Both buttons open the real views unfiltered and say so on hover, rather
  than implying a scope they don't apply. An app-scoped requirements filter is the honest
  follow-up.
- **"New app" is real.** It posts through the existing `createApplication` endpoint from a
  dialog rendered by `Modal` (focus trap and restore, VYB-0768) and invalidates both the
  dashboard and that product's application list. Previously an app could only be created
  from the Hierarchy tab's inline field.
- **"Open app"** drills to the app in the Hierarchy tab, which is where capabilities are
  listed today. The prototype's third state (a full app detail page) is not built.

`tsc --noEmit` clean, `npm run build` clean, **142 passed** — contrast 118 → **136**, the
18 new assertions being the drill-down's own pairings (`.pd-head`, `.pd-glyph`,
`.app-card`, and the three `.app-bdg` states across both themes), all clearing AA.
Verified against the user's screenshot in both themes: `app-card` 322×142 at the app's
content width (prototype ~305×142), `pd-head` 110px tall.

## Session 22 — VYB-0668: the document analysis panel

The frontend half of VYB-0667 (see the backend register for the three-agent pipeline
itself). One panel in the Import Queue's batch view, above the candidate list, because a
reviewer who understands the document first makes better decisions on the candidates it
produced — extraction and analysis are independent and neither gates the other.

- **Amber throughout** (`.ai-panel`), because everything in it is AI output and amber is
  reserved for exactly that. New rules pair `--ai-bd` on `--ai-dim`, the triad the app's
  AI badges already use.
- **The panel shows what the run did not do.** Sections read out of sections total,
  findings kept, noise blocks discarded, quotes rejected for not being in the document,
  and AI call count. A partial run says the remainder was *not read* rather than letting
  a shorter description imply the document was thinner than it is.
- **Unsupported claims render before the description, not after it.** If the verifier
  could not tie a claim back to the document, that is the first thing a reviewer should
  see.
- **Every claim is checkable.** "Show N source findings" lists each finding grouped by
  category with its verbatim quote and where in the document it came from — the thing
  that makes a fluent description auditable rather than merely persuasive.
- **Accept or dismiss, and dismissal takes a reason** in an inline field wired to the
  same endpoint the backend validates. Nothing about displaying the description applies
  it.

`contrast.test.ts` caught three of the new rules on the first run: `.ai-stats`,
`.ai-desc` and `.ai-findings` had been given `--panel-3` hairline borders on a `--panel`
fill, which is not a perceivable boundary. They are boxes on the amber panel, so their
outline is a real boundary and now takes `--line`; only the rulings inside them stayed
hairlines. The gate was right and the fix was the design, not the test.

`tsc --noEmit` clean; suite **154 passed** (contrast 136 → 148 as the panel added 12 real
pairings, all clearing AA).

**Follow-up, same session — the analysis panel is gone.** `DocumentAnalysisPanel` was
deleted. It asked the user to run a second, separate AI step and rendered a "Not analysed
yet" empty state next to a "Not Found" error whenever the running backend predated the
endpoints — three pieces of ceremony for something that should just be part of pressing
Extract candidates.

In its place, `DocumentSummary` renders only when a run exists, with no controls and no
empty state: extraction produces the description, so there is nothing here to start.
Above the candidate list it shows what the document is about, the themes, and one honest
coverage line — findings kept, sections read out of sections total, boilerplate blocks
ignored, claims dropped for not being quotable from the document, and the model. A
partial run still says the remainder was *not read* rather than letting a shorter
description imply a thinner document.

Each candidate card gained an amber chip naming what the agents read it to be (a business
rule, a problem, a constraint), and its existing "Show original text" now reveals the
verbatim sentence from the document that the candidate was derived from.

Extraction errors all land in the one error line under the header — a failed validation
rule, a document with no readable text, or the analysis agents being unreachable or
misconfigured — carrying the backend's own message rather than a generic failure.

`tsc --noEmit` clean; suite **146 passed** (contrast 148 → 140 as the deleted panel's
rules went with it).
