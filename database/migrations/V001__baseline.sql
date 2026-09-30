-- =====================================================================
-- Vyoog — PostgreSQL 16 schema
-- Companion to vyoog-build-specification.md
--
-- Run order matters. This file is the Flyway baseline (V001__baseline.sql).
--
-- Tenancy note (see docs/DECISIONS.md D3): this schema is single-tenant by
-- design. Vyoog runs in its own Postgres schema (vyg_requirement) on the
-- same shared RDS instance as the company's other applications (vyg-pms in
-- vyoog_pms, the pricing tool in its own schema) — schema separation IS the
-- isolation boundary between applications, exactly like those two. There is
-- no tenant_id column and no row-level security here: this deployment
-- serves one organisation, not many. If Vyoog is ever sold as multi-tenant
-- SaaS, that is a deliberate future migration, not something to half-do now.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";
CREATE EXTENSION IF NOT EXISTS "vector";

-- =====================================================================
-- PLATFORM
-- =====================================================================

-- The twelve detectors, global definitions
CREATE TABLE gap_rule_template (
  key         TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  technique   TEXT NOT NULL CHECK (technique IN ('GRAPH','RULE','EMBED','CLASS','LLM')),
  severity    TEXT NOT NULL CHECK (severity IN ('crit','high','ai')),
  description TEXT NOT NULL,
  phase       SMALLINT NOT NULL
);

INSERT INTO gap_rule_template (key,name,technique,severity,description,phase) VALUES
 ('noverify','Missing verification','GRAPH','crit','Approved with no passing test at the current revision',1),
 ('ambig','Unmeasurable wording','RULE','ai','Wording that cannot become a pass or fail condition',1),
 ('orphan','Orphan - no upstream need','GRAPH','crit','No business need or parent requirement satisfies this',1),
 ('nodesign','No downstream design','GRAPH','high','No design node implements this requirement',1),
 ('noac','No acceptance criteria','GRAPH','high','Nothing to write a test against',1),
 ('suspect','Suspect link','GRAPH','high','Upstream changed after the link was last reviewed',1),
 ('compl','Unmapped control clause','EMBED','crit','A control clause no requirement satisfies',4),
 ('untraced','Untraced code change','GRAPH','crit','A commit with no requirement trailer',2),
 ('dup','Duplicate across apps','EMBED','ai','Two apps define the same rule independently',4),
 ('conflict','Conflicting requirements','LLM','crit','Two approved requirements contradict each other',4),
 ('errpath','Happy path only','CLASS','high','Silent on what happens when the normal path fails',4),
 ('nonfr','Missing NFR counterpart','LLM','ai','Functional requirement with no non-functional peer',4);

-- Per-app configuration (was per-tenant; now a single row for this deployment)
CREATE TABLE app_config (
  id             SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),   -- exactly one row
  req_key_prefix TEXT NOT NULL DEFAULT 'VY',
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO app_config (id) VALUES (1);

-- =====================================================================
-- IDENTITY
-- =====================================================================

CREATE TABLE app_user (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  subject       TEXT NOT NULL UNIQUE,          -- Keycloak 'sub'
  email         TEXT NOT NULL,
  display_name  TEXT NOT NULL,
  source        TEXT NOT NULL DEFAULT 'SSO' CHECK (source IN ('SSO','HR','LOCAL')),
  status        TEXT NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE','LEAVE','DEPARTED','EXTERNAL')),
  mfa_enrolled  BOOLEAN NOT NULL DEFAULT false,
  last_seen_at  TIMESTAMPTZ,
  delegate_id   UUID REFERENCES app_user(id),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- NOTE: no password column exists anywhere. This is deliberate (Principle 8).

CREATE TABLE access_grant (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  role        TEXT NOT NULL CHECK (role IN
              ('VIEWER','BUSINESS_ANALYST','REVIEWER','APPROVER','DEVELOPER',
               'TESTER','COMPLIANCE_LEAD','ARCHITECT','ADMINISTRATOR')),
  scope_type  TEXT NOT NULL CHECK (scope_type IN ('PLATFORM','PRODUCT','APP','CAPABILITY','RELEASE')),
  scope_id    UUID,                          -- null only when scope_type = PLATFORM
  granted_by  UUID REFERENCES app_user(id),
  granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at  TIMESTAMPTZ,                   -- mandatory for external users
  revoked_at  TIMESTAMPTZ,
  CHECK (scope_type <> 'PLATFORM' OR scope_id IS NULL),
  CHECK (scope_type =  'PLATFORM' OR scope_id IS NOT NULL)
);
CREATE INDEX ON access_grant (user_id) WHERE revoked_at IS NULL;

CREATE TABLE service_account (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name         TEXT NOT NULL UNIQUE,
  purpose      TEXT,
  client_id    TEXT NOT NULL,                -- Keycloak client
  scopes       TEXT[] NOT NULL DEFAULT '{}',
  key_issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_used_at TIMESTAMPTZ
);

-- =====================================================================
-- PORTFOLIO
-- =====================================================================

CREATE TABLE product (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  key         TEXT NOT NULL UNIQUE,
  name        TEXT NOT NULL,
  tagline     TEXT,
  description TEXT,
  external_ref TEXT,                          -- if a PMS owns this record
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE application (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  product_id  UUID NOT NULL REFERENCES product(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  description TEXT,
  UNIQUE (product_id, name)
);

CREATE TABLE capability (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  application_id UUID NOT NULL REFERENCES application(id) ON DELETE CASCADE,
  name          TEXT NOT NULL,
  code          TEXT,                         -- short code, e.g. 'ATT'
  owner_id      UUID REFERENCES app_user(id),
  UNIQUE (application_id, name)
);

CREATE TABLE glossary_term (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  term        TEXT NOT NULL UNIQUE,
  definition  TEXT NOT NULL,
  owner_id    UUID REFERENCES app_user(id)
);

-- =====================================================================
-- REQUIREMENTS
-- =====================================================================

CREATE TABLE requirement (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  key             TEXT NOT NULL UNIQUE,        -- 'VY-1042'
  capability_id   UUID REFERENCES capability(id),
  type            TEXT NOT NULL CHECK (type IN
                  ('FUNCTIONAL','NON_FUNCTIONAL','BUSINESS_RULE','INTERFACE',
                   'DATA','REPORT','SECURITY','COMPLIANCE')),
  status          TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN
                  ('DRAFT','IN_REVIEW','APPROVED','VERIFIED','REJECTED')),
  priority        TEXT NOT NULL DEFAULT 'MEDIUM'
                  CHECK (priority IN ('CRITICAL','HIGH','MEDIUM','LOW')),
  title           TEXT NOT NULL,
  statement       TEXT NOT NULL,
  rationale       TEXT,
  owner_id        UUID REFERENCES app_user(id),
  developer_id    UUID REFERENCES app_user(id),
  tester_id       UUID REFERENCES app_user(id),
  target_release_id UUID,
  revision        INT  NOT NULL DEFAULT 1,
  quality_score   SMALLINT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by      UUID REFERENCES app_user(id),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by      UUID REFERENCES app_user(id),
  deleted_at      TIMESTAMPTZ
);
CREATE INDEX ON requirement (capability_id, status);
CREATE INDEX ON requirement (owner_id) WHERE deleted_at IS NULL;
CREATE INDEX ON requirement USING gin (statement gin_trgm_ops);

-- Immutable. One row per save. Never updated, never deleted.
CREATE TABLE requirement_revision (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  requirement_id UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  revision       INT  NOT NULL,
  statement      TEXT NOT NULL,
  title          TEXT NOT NULL,
  type           TEXT NOT NULL,
  status         TEXT NOT NULL,
  priority       TEXT NOT NULL,
  capability_id  UUID,
  changed_by     UUID REFERENCES app_user(id),
  changed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  change_reason  TEXT,
  UNIQUE (requirement_id, revision)
);

CREATE TABLE acceptance_criterion (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  requirement_id UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  ordinal        SMALLINT NOT NULL,
  text           TEXT NOT NULL,
  UNIQUE (requirement_id, ordinal)
);

CREATE TABLE requirement_comment (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  requirement_id UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  author_id      UUID NOT NULL REFERENCES app_user(id),
  body           TEXT NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE attachment (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  requirement_id UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  filename       TEXT NOT NULL,
  current_version SMALLINT NOT NULL DEFAULT 1
);

-- Versions are never overwritten (prototype behaviour, kept)
CREATE TABLE attachment_version (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  attachment_id UUID NOT NULL REFERENCES attachment(id) ON DELETE CASCADE,
  version       SMALLINT NOT NULL,
  storage_key   TEXT NOT NULL,
  content_type  TEXT,
  size_bytes    BIGINT,
  uploaded_by   UUID REFERENCES app_user(id),
  uploaded_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (attachment_id, version)
);

-- Fully designed in the prototype, never built. Build it.
CREATE TABLE clarification (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  requirement_id UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  question       TEXT NOT NULL,
  blocks_task    BOOLEAN NOT NULL DEFAULT true,
  raised_by      UUID NOT NULL REFERENCES app_user(id),
  raised_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  assigned_to    UUID REFERENCES app_user(id),
  answer         TEXT,
  answered_by    UUID REFERENCES app_user(id),
  answered_at    TIMESTAMPTZ,
  state          TEXT NOT NULL DEFAULT 'OPEN'
                 CHECK (state IN ('OPEN','ANSWERED','WITHDRAWN')),
  resulted_in_change_request_id UUID
);
CREATE INDEX ON clarification (state) WHERE state = 'OPEN';

-- =====================================================================
-- TRACE GRAPH
-- =====================================================================

CREATE TABLE trace_link (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  from_type  TEXT NOT NULL CHECK (from_type IN
             ('NEED','REQUIREMENT','DESIGN_NODE','CODE','TEST','RELEASE','CLAUSE')),
  from_id    UUID NOT NULL,
  to_type    TEXT NOT NULL CHECK (to_type IN
             ('NEED','REQUIREMENT','DESIGN_NODE','CODE','TEST','RELEASE','CLAUSE')),
  to_id      UUID NOT NULL,
  link_type  TEXT NOT NULL CHECK (link_type IN
             ('SATISFIES','DERIVES','VERIFIES','IMPLEMENTS','REFINES','CONFLICTS')),
  -- the upstream revision at which this link was last reviewed; drives 'suspect'
  reviewed_at_revision INT,
  created_by UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (from_type, from_id, to_type, to_id, link_type)
);
CREATE INDEX ON trace_link (from_type, from_id);
CREATE INDEX ON trace_link (to_type,   to_id);

-- Materialised reachability for hot read paths. Rebuildable from trace_link.
CREATE TABLE trace_closure (
  ancestor_type   TEXT NOT NULL,
  ancestor_id     UUID NOT NULL,
  descendant_type TEXT NOT NULL,
  descendant_id   UUID NOT NULL,
  depth           SMALLINT NOT NULL,
  PRIMARY KEY (ancestor_type, ancestor_id, descendant_type, descendant_id)
);

-- =====================================================================
-- DESIGN
-- =====================================================================

CREATE TABLE design_flow (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  application_id UUID NOT NULL REFERENCES application(id) ON DELETE CASCADE,
  UNIQUE (application_id)
);

CREATE TABLE design_node (
  id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  flow_id  UUID NOT NULL REFERENCES design_flow(id) ON DELETE CASCADE,
  kind     TEXT NOT NULL CHECK (kind IN ('start','step','decision','integration','end')),
  label    TEXT NOT NULL,
  note     TEXT
);

CREATE TABLE design_edge (
  id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  flow_id   UUID NOT NULL REFERENCES design_flow(id) ON DELETE CASCADE,
  from_node UUID NOT NULL REFERENCES design_node(id) ON DELETE CASCADE,
  to_node   UUID NOT NULL REFERENCES design_node(id) ON DELETE CASCADE,
  label     TEXT,
  UNIQUE (from_node, to_node)
);

CREATE TABLE design_node_requirement (
  node_id        UUID NOT NULL REFERENCES design_node(id) ON DELETE CASCADE,
  requirement_id UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  PRIMARY KEY (node_id, requirement_id)
);

-- =====================================================================
-- DETECTION
-- =====================================================================

CREATE TABLE gap_rule (
  rule_key   TEXT PRIMARY KEY REFERENCES gap_rule_template(key),
  enabled    BOOLEAN NOT NULL DEFAULT true,
  threshold  NUMERIC(4,3),
  config     JSONB
);

CREATE TABLE finding (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  rule_key     TEXT NOT NULL REFERENCES gap_rule_template(key),
  fingerprint  TEXT NOT NULL UNIQUE,           -- stable identity across runs
  object_type  TEXT NOT NULL,
  object_id    UUID NOT NULL,
  severity     TEXT NOT NULL,
  title        TEXT NOT NULL,
  detail       TEXT,
  suggestion   TEXT,
  confidence   NUMERIC(4,3),                 -- AI detectors only
  model        TEXT,                         -- model + prompt version, for governance
  state        TEXT NOT NULL DEFAULT 'OPEN'
               CHECK (state IN ('OPEN','ACCEPTED','DISMISSED','RESOLVED')),
  dismiss_reason TEXT,
  actioned_by  UUID REFERENCES app_user(id),
  actioned_at  TIMESTAMPTZ,
  first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  resolved_at   TIMESTAMPTZ
);
CREATE INDEX ON finding (rule_key, severity) WHERE state = 'OPEN';

CREATE TABLE requirement_embedding (
  requirement_id UUID PRIMARY KEY REFERENCES requirement(id) ON DELETE CASCADE,
  revision       INT  NOT NULL,
  model          TEXT NOT NULL,
  embedding      vector(1536) NOT NULL,
  embedded_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON requirement_embedding USING hnsw (embedding vector_cosine_ops);

-- =====================================================================
-- QUALITY
-- =====================================================================

CREATE TABLE review (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  title       TEXT NOT NULL,
  scope_ref   TEXT,
  state       TEXT NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN','CLOSED','BLOCKED')),
  opened_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  closes_at   TIMESTAMPTZ,
  closed_at   TIMESTAMPTZ
);

CREATE TABLE review_item (
  review_id      UUID NOT NULL REFERENCES review(id) ON DELETE CASCADE,
  requirement_id UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  revision       INT NOT NULL,
  PRIMARY KEY (review_id, requirement_id)
);

CREATE TABLE review_participant (
  review_id   UUID NOT NULL REFERENCES review(id) ON DELETE CASCADE,
  user_id     UUID NOT NULL REFERENCES app_user(id),
  role        TEXT NOT NULL CHECK (role IN ('REVIEWER','APPROVER','OBSERVER')),
  signed_at   TIMESTAMPTZ,
  signature_acr TEXT,                        -- step-up level achieved
  PRIMARY KEY (review_id, user_id)
);

CREATE TABLE test_case (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  key         TEXT NOT NULL UNIQUE,
  title       TEXT NOT NULL
);

CREATE TABLE test_run (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  build_label TEXT,
  started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  source      TEXT                            -- CI system identifier
);

-- Verification is bound to the revision that was actually tested.
CREATE TABLE verification (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  requirement_id       UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  requirement_revision INT  NOT NULL,
  test_case_id         UUID REFERENCES test_case(id),
  test_run_id          UUID REFERENCES test_run(id),
  result               TEXT NOT NULL CHECK (result IN ('PASS','FAIL')),
  verified_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON verification (requirement_id, requirement_revision);

CREATE TABLE defect (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  key            TEXT NOT NULL UNIQUE,
  title          TEXT NOT NULL,
  severity       TEXT NOT NULL CHECK (severity IN ('CRITICAL','HIGH','MEDIUM','LOW')),
  requirement_id UUID REFERENCES requirement(id),
  found_in       TEXT NOT NULL CHECK (found_in IN ('DEV','QA','UAT','PRODUCTION')),
  -- the loop that proves requirement quality affects outcomes
  root_cause     TEXT CHECK (root_cause IN
                 ('REQUIREMENT_AMBIGUITY','REQUIREMENT_OMISSION','CODING_ERROR',
                  'ENVIRONMENT','DATA','UNKNOWN')),
  developer_id   UUID REFERENCES app_user(id),
  state          TEXT NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN','FIXED','CLOSED')),
  raised_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =====================================================================
-- DELIVERY
-- =====================================================================

CREATE TABLE brief (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  application_id UUID NOT NULL REFERENCES application(id),
  target         TEXT NOT NULL CHECK (target IN ('CLAUDE_CODE','CODEX','HUMAN')),
  developer_id   UUID REFERENCES app_user(id),
  baseline_id    UUID,                        -- what it was generated against
  content        TEXT NOT NULL,
  generated_by   UUID REFERENCES app_user(id),
  generated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  stale          BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE brief_requirement (
  brief_id       UUID NOT NULL REFERENCES brief(id) ON DELETE CASCADE,
  requirement_id UUID NOT NULL REFERENCES requirement(id),
  revision       INT NOT NULL,               -- staleness compares against this
  PRIMARY KEY (brief_id, requirement_id)
);

-- =====================================================================
-- RELEASES
-- =====================================================================

CREATE TABLE release (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name       TEXT NOT NULL UNIQUE,
  state      TEXT NOT NULL DEFAULT 'PLANNED'
             CHECK (state IN ('PLANNED','OPEN','FROZEN','RELEASED'))
);

CREATE TABLE release_scope_item (
  release_id     UUID NOT NULL REFERENCES release(id) ON DELETE CASCADE,
  requirement_id UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  PRIMARY KEY (release_id, requirement_id)
);

CREATE TABLE scope_movement (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  release_id     UUID NOT NULL REFERENCES release(id) ON DELETE CASCADE,
  requirement_id UUID NOT NULL REFERENCES requirement(id),
  direction      TEXT NOT NULL CHECK (direction IN ('IN','OUT')),
  reason         TEXT,
  moved_by       UUID REFERENCES app_user(id),
  moved_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE baseline (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name         TEXT NOT NULL UNIQUE,
  release_id   UUID REFERENCES release(id),
  frozen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  frozen_by    UUID REFERENCES app_user(id),
  gaps_at_freeze INT NOT NULL DEFAULT 0
);

CREATE TABLE baseline_item (
  baseline_id    UUID NOT NULL REFERENCES baseline(id) ON DELETE CASCADE,
  requirement_id UUID NOT NULL REFERENCES requirement(id),
  revision       INT NOT NULL,
  PRIMARY KEY (baseline_id, requirement_id)
);

CREATE TABLE variant (
  id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name      TEXT NOT NULL UNIQUE
);

CREATE TABLE variant_applicability (
  variant_id     UUID NOT NULL REFERENCES variant(id) ON DELETE CASCADE,
  requirement_id UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  applies        BOOLEAN NOT NULL DEFAULT true,
  PRIMARY KEY (variant_id, requirement_id)
);

CREATE TABLE environment (
  id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name      TEXT NOT NULL UNIQUE,
  ordinal   SMALLINT NOT NULL DEFAULT 0
);

CREATE TABLE deployment (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  environment_id UUID NOT NULL REFERENCES environment(id),
  build_label    TEXT NOT NULL,
  deployed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  succeeded      BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE deployment_requirement (
  deployment_id  UUID NOT NULL REFERENCES deployment(id) ON DELETE CASCADE,
  requirement_id UUID NOT NULL REFERENCES requirement(id),
  revision       INT NOT NULL,
  PRIMARY KEY (deployment_id, requirement_id)
);

-- =====================================================================
-- INTAKE
-- =====================================================================

CREATE TABLE document (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  key         TEXT NOT NULL UNIQUE,
  title       TEXT NOT NULL,
  product_id  UUID REFERENCES product(id),
  revision    INT NOT NULL DEFAULT 1,
  state       TEXT NOT NULL DEFAULT 'DRAFT'
);

CREATE TABLE import_batch (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  filename     TEXT NOT NULL,
  application_id UUID REFERENCES application(id),
  upload_kind  TEXT NOT NULL DEFAULT 'FREEFORM'
               CHECK (upload_kind IN ('FREEFORM','STANDARD_SPEC','REQIF','EXCEL')),
  uploaded_by  UUID REFERENCES app_user(id),
  uploaded_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  state        TEXT NOT NULL DEFAULT 'PARSED'
);

CREATE TABLE import_candidate (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  batch_id      UUID NOT NULL REFERENCES import_batch(id) ON DELETE CASCADE,
  tag           TEXT,
  statement     TEXT NOT NULL,               -- editable before acceptance
  capability_id UUID REFERENCES capability(id),
  criteria_count SMALLINT NOT NULL DEFAULT 0,
  quality_score SMALLINT,
  flags         JSONB,                        -- detector output
  selected      BOOLEAN NOT NULL DEFAULT false,
  committed_requirement_id UUID REFERENCES requirement(id)
);

CREATE TABLE change_request (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  key         TEXT NOT NULL UNIQUE,
  title       TEXT NOT NULL,
  raised_by   UUID REFERENCES app_user(id),
  state       TEXT NOT NULL DEFAULT 'OPEN'
              CHECK (state IN ('OPEN','APPROVED','REJECTED','APPLIED')),
  impact_requirements INT NOT NULL DEFAULT 0,
  impact_apps         INT NOT NULL DEFAULT 0,
  decided_by  UUID REFERENCES app_user(id),
  decided_at  TIMESTAMPTZ
);

-- =====================================================================
-- PLATFORM SUPPORT
-- =====================================================================

CREATE TABLE audit_event (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  actor_id    UUID,
  actor_type  TEXT NOT NULL DEFAULT 'USER' CHECK (actor_type IN ('USER','SERVICE','SYSTEM')),
  action      TEXT NOT NULL,
  object_type TEXT,
  object_id   UUID,
  before      JSONB,
  after       JSONB,
  request_id  TEXT,
  ip          INET
);
CREATE INDEX ON audit_event (occurred_at DESC);

-- Append-only enforcement
CREATE OR REPLACE FUNCTION audit_is_append_only() RETURNS TRIGGER AS $fn$
BEGIN
  RAISE EXCEPTION 'audit_event is append-only';
END;
$fn$ LANGUAGE plpgsql;

CREATE TRIGGER audit_no_update BEFORE UPDATE OR DELETE ON audit_event
  FOR EACH ROW EXECUTE FUNCTION audit_is_append_only();

CREATE TABLE outbox_event (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_type   TEXT NOT NULL,
  payload      JSONB NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at TIMESTAMPTZ
);
CREATE INDEX ON outbox_event (published_at) WHERE published_at IS NULL;

CREATE TABLE notification (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  tone       TEXT NOT NULL DEFAULT 'info',
  title      TEXT NOT NULL,
  subtitle   TEXT,
  link       TEXT,
  read_at    TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE integration_connection (
  key        TEXT PRIMARY KEY,                -- 'git' | 'jira' | 'ci' | 'hr' | 'planning'
  connected  BOOLEAN NOT NULL DEFAULT false,
  config     JSONB
);

-- =====================================================================
-- DERIVED VIEWS
-- =====================================================================

-- Principle 5: verification is a predicate, never a stored flag.
CREATE VIEW requirement_verification_state AS
SELECT r.id,
  EXISTS (SELECT 1 FROM verification v
           WHERE v.requirement_id = r.id
             AND v.requirement_revision = r.revision
             AND v.result = 'PASS')                       AS is_verified,
  EXISTS (SELECT 1 FROM verification v
           WHERE v.requirement_id = r.id
             AND v.requirement_revision < r.revision
             AND v.result = 'PASS')                       AS has_stale_evidence
FROM requirement r;

-- Coverage pips U D C T
CREATE VIEW requirement_coverage AS
SELECT r.id,
  EXISTS (SELECT 1 FROM trace_link t WHERE t.to_type='REQUIREMENT' AND t.to_id=r.id
            AND t.link_type IN ('SATISFIES','DERIVES'))   AS has_upstream,
  EXISTS (SELECT 1 FROM design_node_requirement d WHERE d.requirement_id=r.id) AS has_design,
  EXISTS (SELECT 1 FROM trace_link t WHERE t.from_type='CODE' AND t.to_id=r.id) AS has_code,
  EXISTS (SELECT 1 FROM verification v WHERE v.requirement_id=r.id
            AND v.requirement_revision=r.revision AND v.result='PASS')          AS has_test
FROM requirement r;
