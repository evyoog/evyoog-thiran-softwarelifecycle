<!-- Split verbatim from the former backend/docs/vyoog-build-specification.md (lines 774–913). Section numbers are unchanged; docs/02-requirements/SPECIFICATION-INDEX.md maps every section to its file. -->

## 6. Gap detection — the differentiator

Twelve detectors. Each has a key, a technique, and a severity. **Seven need no AI at
all** — build those first and ship a genuinely useful product before any model is
involved.

| # | Key | Name | Technique | Severity | Phase |
|---|---|---|---|---|---|
| 1 | `noverify` | Missing verification | GRAPH | crit | 1 |
| 2 | `ambig` | Unmeasurable wording | RULE | ai | 1 |
| 3 | `orphan` | Orphan — no upstream need | GRAPH | crit | 1 |
| 4 | `nodesign` | No downstream design | GRAPH | high | 1 |
| 5 | `noac` | No acceptance criteria | GRAPH | high | 1 |
| 6 | `suspect` | Suspect link | GRAPH | high | 1 |
| 7 | `compl` | Unmapped control clause | EMBED | crit | 4 |
| 8 | `untraced` | Untraced code change | GRAPH | crit | 2 |
| 9 | `dup` | Duplicate across apps | EMBED | ai | 4 |
| 10 | `conflict` | Conflicting requirements | LLM | crit | 4 |
| 11 | `errpath` | Happy path only | CLASS | high | 4 |
| 12 | `nonfr` | Missing NFR counterpart | LLM | ai | 4 |

### 6.1 Detector contract

```java
public interface GapDetector {
  String key();
  Technique technique();
  /** Pure function of tenant state → findings. Must be idempotent. */
  List<FindingCandidate> detect(DetectionContext ctx);
}
```

Rules:

- A detector **never writes** to domain tables. It emits candidates; the engine
  reconciles them against existing findings.
- Reconciliation is by **stable fingerprint**, not row identity:
  `sha256(rule_key | object_type | object_id | discriminator)`. Same fingerprint on a
  later run means the same finding — do not create a duplicate, do not resurrect a
  dismissal.
- A finding whose fingerprint disappears from a run is **auto-resolved** with
  `resolution = FIXED`.
- A dismissed finding stays dismissed unless the underlying object's revision changes.
  Dismissal carries a reason and an actor — those reasons are what produce the published
  false-positive rate (Principle 7).

### 6.2 The seven no-AI detectors, as SQL

```sql
-- 1. noverify: approved but no passing test against the current revision. VERIFIED is
-- not a status (D16) — APPROVED is the pipeline's terminal one, so it is the only
-- status this needs to check.
SELECT r.id FROM requirement r
 WHERE r.status = 'APPROVED'
   AND NOT EXISTS (SELECT 1 FROM verification v
                    WHERE v.requirement_id = r.id
                      AND v.requirement_revision = r.revision
                      AND v.result = 'PASS');

-- 3. orphan: nothing upstream satisfies a need
SELECT r.id FROM requirement r
 WHERE NOT EXISTS (SELECT 1 FROM trace_link t
                    WHERE t.to_type = 'REQUIREMENT' AND t.to_id = r.id
                      AND t.link_type IN ('SATISFIES','DERIVES'));

-- 4. nodesign: no design node implements it
SELECT r.id FROM requirement r
 WHERE NOT EXISTS (SELECT 1 FROM design_node_requirement d
                    WHERE d.requirement_id = r.id);

-- 5. noac
SELECT r.id FROM requirement r
 WHERE NOT EXISTS (SELECT 1 FROM acceptance_criterion a
                    WHERE a.requirement_id = r.id);

-- 6. suspect: upstream moved after the link was last reviewed
SELECT t.id FROM trace_link t
  JOIN requirement up ON up.id = t.from_id AND t.from_type = 'REQUIREMENT'
 WHERE t.reviewed_at_revision IS NOT NULL
   AND up.revision > t.reviewed_at_revision;

-- 8. untraced: a commit with no requirement trailer
SELECT c.id FROM code_change c
 WHERE NOT EXISTS (SELECT 1 FROM trace_link t
                    WHERE t.from_type = 'CODE' AND t.from_id = c.id);
```

Detector 2 (`ambig`) is a lexicon plus regex, not a model. **The prototype's
implementation is the specification** — port `AMBIG_TERMS`, the weak-modal check, the
compound-requirement heuristic and the length check from `VyoogApp.jsx` verbatim,
including the suggested replacement for each term. It already detects: `promptly`,
`quickly`, `appropriate`, `adequate`, `sufficient`, `reasonable`, `efficient`,
`user-friendly`, `intuitive`, `seamless`, `robust`, `flexible`, `scalable`, `optimal`,
`minimal`, `several`, `many`, `few`, `various`, `etc`, `approximately`, `improved`,
`better`, `easy`, `easily`, `simple`, `simply`, `quick`, `fast`, `clearly`, `properly`,
`correctly`, `acceptable`, `significant`, `where possible`, `if possible`, `up to date`,
`timely`, `regularly`, `periodically`, `as needed`, `as required`, `if necessary`,
`state-of-the-art`, `best practice`, `and so on`.

### 6.3 The AI detectors (Phase 4)

**Embedding pipeline.** On requirement save, enqueue an embedding job. Store in
`requirement_embedding` with the model name and the revision embedded, so a model change
is a re-embed and not silent drift.

- **`dup`** — cosine similarity ≥ 0.85 between requirements in *different* apps.
  Below 0.85 down to 0.70, surface only on the create screen as a soft hint.
- **`compl`** — embed control clauses; a clause with no requirement above 0.75 is
  unmapped.
- **`conflict`** — embeddings shortlist candidate pairs above 0.80; an LLM adjudicates
  whether they genuinely contradict. Never auto-create; always a proposal.
  **With AI off (the default) the rule is reported unavailable, quietly (VYB-0911):** the
  detector checks that an adjudicator is configured before it queries or spends any AI-call
  budget, existing conflict findings are left untouched (never resolved because the check
  could not run), and it logs one INFO line the first time (`AI_ENABLED`, `AI_API_KEY`), not
  an error per requirement write. A configured provider that is down is also reported
  unavailable, as a warning without a stack trace.
- **`errpath`** — a small classifier, or a rule fallback: no error/failure vocabulary
  present. The prototype's `ERR_WORDS` list is the fallback specification.
- **`nonfr`** — a Functional requirement in a capability with no Non-Functional peer.
  Graph-detectable; LLM only improves the suggested counterpart text.

**Governance for every AI output:**

- Store the model, prompt version, and confidence with the finding.
- Confidence below threshold means it is not shown at all, rather than shown as weak.
- Every AI finding renders amber (Principle 3) and is never applied automatically.
- Track and publish the dismissal rate per detector. A detector above ~30% dismissal is
  disabled by default for new tenants until retuned.

### 6.4 When detection runs

| Trigger | Scope |
|---|---|
| Requirement created / updated / status change | That requirement plus its immediate graph neighbours |
| Trace link created / deleted | Both endpoints |
| Test result ingested | The requirement under test |
| Commit webhook | The referenced requirements, plus `untraced` for the commit |
| Nightly | Full tenant sweep; reconciles anything the event path missed |
| Manual "Rescan" | Full tenant sweep, rate-limited to one per five minutes |

Run under a Redis lock keyed by `tenant_id` so two sweeps cannot overlap. Emit
`DetectionRunCompleted` with counts per rule for the trend chart on Home.

---
