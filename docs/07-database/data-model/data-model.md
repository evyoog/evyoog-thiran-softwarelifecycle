<!-- Split verbatim from the former backend/docs/vyoog-build-specification.md (lines 533–773). Section numbers are unchanged; docs/02-requirements/SPECIFICATION-INDEX.md maps every section to its file. -->

> The schema that runs is the Flyway migrations in `database/migrations`. Where this section mentions `tenant_id` or row-level security, that was dropped in D3 and is not in the migrations.

## 5. Data model

The schema is the Flyway migrations (`backend/vyoog-domain/src/main/resources/db/migration`); the original design-time `vyoog-schema.sql` has been removed (VYB-0905). This section explains the decisions the schema encodes, as originally designed: where it mentions `tenant_id` or row-level security, that was dropped in `DECISIONS.md` D3 and is not in the migrations.

### 5.1 Conventions

- Primary keys: `UUID` (v7 where available, for index locality).
- Every tenant-owned table: `tenant_id UUID NOT NULL`, RLS enabled.
- Audit columns on every table: `created_at`, `created_by`, `updated_at`, `updated_by`.
- Soft delete via `deleted_at` only where a record must survive for audit
  (requirements, findings, grants). Everything else deletes hard.
- Money and duration columns: **none anywhere** (Principle 10).
- Timestamps: `TIMESTAMPTZ`, always UTC.

### 5.2 Core entities

```
tenant

app_user                  mirror of a Keycloak subject
access_grant              user × role × scope        ← Principle 9
service_account

product
application               ("app" is a reserved word in too many places)
capability

requirement               the current, mutable head row
requirement_revision      immutable snapshot, one per save
acceptance_criterion      child of requirement, ordered
requirement_comment
attachment / attachment_version

trace_link                the graph edge
trace_closure             materialised reachability      ← §5.4
```

### 5.3 Requirement, revision, and why "Verified" evidence is strict (but not a status)

`requirement` holds the current state. Every save writes a `requirement_revision` row
and increments `requirement.revision`. Revisions are never updated or deleted.

```sql
requirement (
  id, tenant_id, key,            -- key = human ID, e.g. 'VY-1042', unique per tenant
  capability_id, type, status, priority,
  title, statement,
  owner_id, target_release_id,
  revision      INT NOT NULL DEFAULT 1,
  quality_score SMALLINT,
  ...
)

requirement_revision (
  id, tenant_id, requirement_id,
  revision INT NOT NULL,
  statement TEXT NOT NULL,
  type, status, priority, capability_id,   -- full snapshot, not a diff
  changed_by, changed_at, change_reason,
  UNIQUE (requirement_id, revision)
)
```

**Verification is bound to a revision:**

```sql
verification (
  id, tenant_id,
  requirement_id,
  requirement_revision INT NOT NULL,   -- the revision that was actually tested
  test_case_id,
  test_run_id,
  result        -- PASS | FAIL
  verified_at
)
```

`is_verified` (below) is true when a `verification` row exists with `result = PASS`
**and** `requirement_revision = requirement.revision`. Bump the revision and it silently
stops being true — no job required, it falls out of the predicate. This is Principle 5,
and implementing it as a stored flag instead of a derived predicate is the most likely
way to get it wrong.

**D16: this predicate does not drive `requirement.status`.** It used to — a passing test
promoted APPROVED straight to a `VERIFIED` status — but that coupling was removed.
APPROVED is now the requirement lifecycle's terminal status, and "verifying" a
requirement is a person's action on an IN_REVIEW one (see §8, `IN_REVIEW → REVIEWED`),
not something CI reports back automatically. `is_verified`/`has_stale_evidence` are still
real and still used — by `ReleaseService#readiness`, and by Quality → Verification — as
quality-reporting facts about a requirement, independent of what stage it is at.

Expose it as a view:

```sql
CREATE VIEW requirement_verification_state AS
SELECT r.id, r.tenant_id,
       EXISTS (SELECT 1 FROM verification v
                WHERE v.requirement_id = r.id
                  AND v.requirement_revision = r.revision
                  AND v.result = 'PASS')  AS is_verified,
       EXISTS (SELECT 1 FROM verification v
                WHERE v.requirement_id = r.id
                  AND v.requirement_revision < r.revision
                  AND v.result = 'PASS')  AS has_stale_evidence
FROM requirement r;
```

`has_stale_evidence` is what Quality → Verification counts as "stale evidence".

### 5.4 The trace graph: edge table plus closure table

This is decision **A1** and it was contested. The answer is Postgres, not Neo4j.

```sql
trace_link (
  id, tenant_id,
  from_type, from_id,      -- NEED | REQUIREMENT | DESIGN_NODE | CODE | TEST | RELEASE
  to_type,   to_id,
  link_type,               -- SATISFIES | DERIVES | VERIFIES | IMPLEMENTS | REFINES | CONFLICTS
  created_by, created_at,
  reviewed_at_revision INT,   -- upstream revision when this link was last reviewed
  UNIQUE (tenant_id, from_type, from_id, to_type, to_id, link_type)
)
```

**`reviewed_at_revision` is what makes suspect-link detection possible.** When the
upstream item's revision exceeds this value, the link is suspect — the dependant was
never revisited after its parent changed. Detector 6 is one comparison.

**Reachability** is needed constantly (impact analysis, orphan detection, coverage
rollups). Two mechanisms, used for different jobs:

*Recursive CTE* for ad-hoc traversal, depth-limited:

```sql
WITH RECURSIVE downstream AS (
  SELECT to_type, to_id, 1 AS depth
    FROM trace_link
   WHERE from_type = 'REQUIREMENT' AND from_id = :id
  UNION ALL
  SELECT tl.to_type, tl.to_id, d.depth + 1
    FROM trace_link tl
    JOIN downstream d ON tl.from_type = d.to_type AND tl.from_id = d.to_id
   WHERE d.depth < 12
)
SELECT DISTINCT * FROM downstream;
```

The `depth < 12` guard is mandatory. Requirement graphs contain cycles in practice
(A refines B, B conflicts with A) and an unguarded CTE will not terminate.

*Closure table* for hot read paths — coverage percentages, grid pips, dashboard rollups:

```sql
trace_closure (
  tenant_id, ancestor_type, ancestor_id, descendant_type, descendant_id,
  depth SMALLINT,
  PRIMARY KEY (tenant_id, ancestor_type, ancestor_id, descendant_type, descendant_id)
)
```

Maintained incrementally on link insert/delete inside the same transaction. Rebuildable
from `trace_link` by a maintenance job; add a nightly consistency check that compares a
sample and alerts on drift.

**Why not Neo4j:** the graph is small (hundreds of thousands of edges, not billions),
every traversal is tenant-scoped and depth-limited, and the alternative costs a second
datastore, a second consistency model, and distributed transactions between the register
and the graph. Postgres wins on every axis that matters here.

### 5.5 Remaining tables by area

**Portfolio** — `product`, `application`, `capability`, `glossary_term`,
`glossary_term_usage`

**Documents and intake** — `document`, `document_section`, `import_batch`,
`import_candidate` (with `candidate_flag` child rows for detector output),
`import_extraction_step` (VYB-0940: the finished steps of an AI extraction, kept so a failed one can be continued; `import_batch.state` gains EXTRACTING and EXTRACTION_FAILED, with `extraction_started_at` and `extraction_error`; see [`../../08-architecture/backend-architecture/network-calls-and-transactions.md`](../../08-architecture/backend-architecture/network-calls-and-transactions.md)),
`change_request`, `change_impact`

**Design** — `design_flow` (one per application), `design_node`, `design_edge`,
`design_node_requirement`

**Detection** — `gap_rule_template` (global), `gap_rule` (tenant, enable/disable/tune),
`finding`, `finding_state` (OPEN / ACCEPTED / DISMISSED with reason and actor),
`requirement_embedding` (pgvector)

**Quality** — `review`, `review_item`, `review_participant`, `review_comment`,
`test_case`, `test_run`, `test_result`, `verification`, `defect`; manual test management
(VYB-0923, see [`test-management.md`](test-management.md)): `test_plan`, `test_suite`,
`test_suite_case`, `test_step`, `test_run_case`, `test_run_step`; defect lifecycle (VYB-0931, see
[`../../04-workflows/defect-lifecycle.md`](../../04-workflows/defect-lifecycle.md)): `defect_transition`, `defect_comment`,
and `defect.test_case_id`, `test_run_id`, `release_id`

**Delivery** — `brief`, `brief_requirement`, `brief_target`

**Releases** — `release`, `release_scope_item`, `scope_movement`, `baseline`,
`baseline_item`, `variant`, `variant_applicability`, `environment`, `deployment`,
`deployment_requirement`, `release_note`

**Collaboration** — `clarification` (§7.4.7), `notification`, `inbox_item`

**Platform** — `audit_event`, `integration_connection`, `outbox_event`; `ai_proposal` (VYB-0938, see [`../../04-workflows/ai-proposal-review.md`](../../04-workflows/ai-proposal-review.md)); `ai_call` (VYB-0939, one row per model call, no user and no text; see [`../../08-architecture/backend-architecture/ai-usage-and-budgets.md`](../../08-architecture/backend-architecture/ai-usage-and-budgets.md)); `app_config.ai_token_budget_daily` and `ai_token_budget_monthly` (VYB-0939); `app_config.ai_redaction_disabled` (VYB-0937, see [`../../08-architecture/security/ai-redaction.md`](../../08-architecture/security/ai-redaction.md))

### 5.6 Audit

```sql
audit_event (
  id, tenant_id, occurred_at,
  actor_id, actor_type,        -- USER | SERVICE | SYSTEM
  action,                      -- REQUIREMENT_APPROVED, GRANT_REVOKED, ...
  object_type, object_id,
  before JSONB, after JSONB,
  request_id, ip, user_agent
)
```

Append-only, enforced by a `BEFORE UPDATE OR DELETE` trigger that raises an exception.
Partition monthly. Never expose a delete endpoint.

### 5.7 Indexing that actually matters

```sql
-- every tenant-scoped lookup leads with tenant_id
CREATE INDEX ON requirement (tenant_id, capability_id, status);
CREATE INDEX ON requirement (tenant_id, owner_id) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX ON requirement (tenant_id, key);

-- graph traversal in both directions
CREATE INDEX ON trace_link (tenant_id, from_type, from_id);
CREATE INDEX ON trace_link (tenant_id, to_type,   to_id);

-- fuzzy text search for duplicate candidates and the grid's text filter
CREATE INDEX ON requirement USING gin (statement gin_trgm_ops);

-- similarity search
CREATE INDEX ON requirement_embedding
  USING hnsw (embedding vector_cosine_ops);

-- open findings, the most-read query in the product
CREATE INDEX ON finding (tenant_id, rule_key, severity)
  WHERE state = 'OPEN';
```

---
