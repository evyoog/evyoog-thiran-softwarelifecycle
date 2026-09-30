<!-- Split verbatim from the former backend/docs/vyoog-build-specification.md (lines 981–1094). Section numbers are unchanged; docs/02-requirements/SPECIFICATION-INDEX.md maps every section to its file. -->

### 7.4 Requirements

The largest module.

#### 7.4.1 Grid

The data grid is a substantial component in its own right. Required behaviours, all
present in the prototype:

- Column show/hide, **drag to reorder**, resize, pin left
- Sort, multi-column filter (text and select per column type)
- **Group by** capability / status / owner / priority / type / release, collapsible
- Row density: compact / normal / relaxed
- Row selection, select-all-in-filter
- **Inline cell edit** — double-click, `Tab` commits and moves to the next editable cell,
  `Esc` reverts
- **Bulk edit modal** — change priority/status/type/capability/release/owner across a
  selection; "leave unchanged" is the default for every field; writes to each
  requirement's history; undoable as one action
- **Coverage pips** `U D C T` on every row — upstream, design, code, test. Red where
  missing (Principle 2)
- Quality score column; gap count column
- Expand row for inline detail
- Virtualised rendering — the register reaches tens of thousands of rows
- Server-side data source behind a single interface so paging/sorting/filtering move to
  the backend without touching the component

#### 7.4.2 Detail panel

Attributes; statement editor with autosave draft; acceptance criteria; **attachments with
versioning** (never overwrite, keep every version); traceability up and down;
**lifecycle and accountability timeline** (who authored, reviewed, approved, developed,
tested, deployed — gated by status rank so nothing claims a stage that has not happened);
discussion thread; *Raise clarification*; *Raise defect*.

**Concurrent-edit protection.** The panel shows who else has the requirement open. On
save, the server compares the client's revision. On mismatch it returns `409` with both
versions, and the UI presents a three-way choice: keep theirs, keep mine, or merge. Both
versions remain in history regardless. Never last-write-wins.

#### 7.4.3 New requirement screen

A full authoring screen, not a modal. Built and specified in the prototype.

- Placement: Product → App → Capability, cascading; unplaced is allowed but warned
- Statement with **live linting** — the `ambig` detector runs as you type, plus missing
  `shall`, weak modals, compound requirements, excessive length
- Each ambiguous term shows an inline suggested replacement; **never auto-applied**
- Classification: type, priority, target release, owner
- Acceptance criteria: repeatable rows
- Traceability: upstream need required; downstream is read-only ("links are made as work
  happens, never typed here")
- **Live gap preview** — which of the twelve will fire on save, split into *avoidable*
  and *expected* (`noverify` and `nodesign` always fire on a new requirement and are
  shown greyed, not alarming)
- Live quality score ring
- **Duplicate detection** against the register as you type
- Save as draft · Save and add another (keeps placement) · Submit for review
- Confirm on submit **only** when it would waste a reviewer's time (no criteria, or score
  below 60) — the rail already lists the gaps, so a modal repeating them is noise

#### 7.4.4 Documents

Register of specification documents (title, ID, product, item count, revision, status,
gaps, updated). Document view renders requirements as readable prose rather than a grid.
Export to Word and ReqIF.

#### 7.4.5 Matrix

Requirement × test-case coverage matrix, four cell states (none / verified / linked-not-run
/ suspect), per-test totals in the footer, "show only gaps" filter. Second tab: suspect
links — upstream changed, dependants never revisited.

#### 7.4.6 Graph

Interactive trace graph from business need through requirement, design, code and test.
Chains that stop short are highlighted. Depth-limited; lazy-expand on click.

#### 7.4.7 Change requests and the Clarification object

**Change request** — a change to an approved requirement is itself an approvable item.
Impact volume is computed from the graph *before* anyone rules on it. On acceptance,
every downstream item is marked **suspect** rather than silently invalidated.

**Clarification** — fully designed, never built. Build it.

```
clarification (
  id, tenant_id, requirement_id, raised_by, raised_at,
  question TEXT,
  blocks_task BOOLEAN,          -- does this stop work?
  assigned_to,                  -- who must answer
  answered_by, answered_at, answer TEXT,
  state,                        -- OPEN | ANSWERED | WITHDRAWN
  resulted_in_change_request_id -- nullable
)
```

Rules: an open clarification with `blocks_task` blocks the derived task for its
requirement and shows in My Work as blocked. Ageing clarifications escalate. An answer
that changes meaning must produce a change request rather than an edit — enforce by
offering that path in the UI when the answer is accepted.

#### 7.4.8 Import queue

Upload a specification; the parser splits it into candidate requirements. Each candidate
shows extracted text (**editable inline before acceptance**), detected capability,
acceptance-criteria count, quality score, and detector flags with suggested fixes.
Accept / accept-with-fix / import-as-written / skip, per candidate. Bulk import of the
selection as Draft. Nothing enters the register without explicit acceptance.

Distinguish a **standard requirement document** upload type with stricter validation and
a separate capability-confirmation step.
