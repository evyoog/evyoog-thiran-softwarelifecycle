-- =====================================================================
-- Vyoog — PostgreSQL 16 schema
-- Companion to vyoog-build-specification.md
--
-- Run order matters. This file is the Flyway baseline (V001__baseline.sql).
-- Every tenant-owned table carries tenant_id and enables RLS; the helper
-- at the bottom applies the policy uniformly so none is forgotten.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";
CREATE EXTENSION IF NOT EXISTS "vector";

-- ---------------------------------------------------------------------
-- Roles.  The application MUST NOT be the table owner or RLS is bypassed.
-- ---------------------------------------------------------------------
-- CREATE ROLE vyoog_migrator LOGIN PASSWORD '...';   -- owns schema, runs Flyway
-- CREATE ROLE vyoog_app      LOGIN PASSWORD '...';   -- DML only, subject to RLS
-- GRANT USAGE ON SCHEMA public TO vyoog_app;

-- =====================================================================
-- PLATFORM (not tenant-scoped)
-- =====================================================================

CREATE TABLE tenant (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  slug          TEXT NOT NULL UNIQUE,
  name          TEXT NOT NULL,
  status        TEXT NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE','SUSPENDED','DELETED')),
  req_key_prefix TEXT NOT NULL DEFAULT 'VY',
  idp_alias     TEXT,                       -- Keycloak IdP alias for this customer
  email_domains TEXT[],                     -- home-IdP discovery
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at    TIMESTAMPTZ
);

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

-- =====================================================================
-- IDENTITY
-- =====================================================================

CREATE TABLE app_user (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     UUID NOT NULL REFERENCES tenant(id),
  subject       TEXT NOT NULL,              -- Keycloak 'sub'
  email         TEXT NOT NULL,
  display_name  TEXT NOT NULL,
  source        TEXT NOT NULL DEFAULT 'SSO' CHECK (source IN ('SSO','HR','LOCAL')),
  status        TEXT NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE','LEAVE','DEPARTED','EXTERNAL')),
  mfa_enrolled  BOOLEAN NOT NULL DEFAULT false,
  last_seen_at  TIMESTAMPTZ,
  delegate_id   UUID REFERENCES app_user(id),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, subject)
);
-- NOTE: no password column exists anywhere. This is deliberate (Principle 8).

CREATE TABLE access_grant (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant(id),
  user_id     UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  role        TEXT NOT NULL CHECK (role IN
              ('VIEWER','BUSINESS_ANALYST','REVIEWER','APPROVER','DEVELOPER',
               'TESTER','COMPLIANCE_LEAD','ARCHITECT','ADMINISTRATOR')),
  scope_type  TEXT NOT NULL CHECK (scope_type IN ('TENANT','PRODUCT','APP','CAPABILITY','RELEASE')),
  scope_id    UUID,                          -- null only when scope_type = TENANT
  granted_by  UUID REFERENCES app_user(id),
  granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at  TIMESTAMPTZ,                   -- mandatory for external users
  revoked_at  TIMESTAMPTZ,
  CHECK (scope_type <> 'TENANT' OR scope_id IS NULL),
  CHECK (scope_type =  'TENANT' OR scope_id IS NOT NULL)
);
CREATE INDEX ON access_grant (tenant_id, user_id) WHERE revoked_at IS NULL;

CREATE TABLE service_account (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    UUID NOT NULL REFERENCES tenant(id),
  name         TEXT NOT NULL,
  purpose      TEXT,
  client_id    TEXT NOT NULL,                -- Keycloak client
  scopes       TEXT[] NOT NULL DEFAULT '{}',
  key_issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_used_at TIMESTAMPTZ,
  UNIQUE (tenant_id, name)
);

-- =====================================================================
-- PORTFOLIO
-- =====================================================================

CREATE TABLE product (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant(id),
  key         TEXT NOT NULL,
  name        TEXT NOT NULL,
  tagline     TEXT,
  description TEXT,
  external_ref TEXT,                          -- if a PMS owns this record
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, key)
);

CREATE TABLE application (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant(id),
  product_id  UUID NOT NULL REFERENCES product(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  description TEXT,
  UNIQUE (tenant_id, product_id, name)
);

CREATE TABLE capability (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     UUID NOT NULL REFERENCES tenant(id),
  application_id UUID NOT NULL REFERENCES application(id) ON DELETE CASCADE,
  name          TEXT NOT NULL,
  code          TEXT,                         -- short code, e.g. 'ATT'
  owner_id      UUID REFERENCES app_user(id),
  UNIQUE (tenant_id, application_id, name)
);

CREATE TABLE glossary_term (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant(id),
  term        TEXT NOT NULL,
  definition  TEXT NOT NULL,
  owner_id    UUID REFERENCES app_user(id),
  UNIQUE (tenant_id, term)
);

-- =====================================================================
-- REQUIREMENTS
-- =====================================================================

CREATE TABLE requirement (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant(id),
  key             TEXT NOT NULL,              -- 'VY-1042'
  capability_id   UUID REFERENCES capability(id),
  type            TEXT NOT NULL CHECK (type IN
                  ('FUNCTIONAL','NON_FUNCTIONAL','BUSINESS_RULE','INTERFACE',
                   'DATA','REPORT','SECURITY','COMPLIANCE')),
  status          TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN
                  ('DRAFT','IN_REVIEW','REVIEWED','NEEDS_REVISION','APPROVED','REJECTED')),
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
  deleted_at      TIMESTAMPTZ,
  -- VYB-0813 (D17): transition metadata, set automatically by Requirement#transitionTo
  -- on every status change. The durable, append-only history of every transition is
  -- audit_event, not these — these are convenience columns for "what/when/why was the
  -- most recent one", so a screen or query doesn't need to join out to audit_event for
  -- the common case.
  previous_status TEXT,                          -- null until the first transition
  revision_count  INT  NOT NULL DEFAULT 0,        -- times this has entered NEEDS_REVISION
  reason          TEXT,                           -- reason given for the most recent transition
  changed_by      UUID REFERENCES app_user(id),   -- who made the most recent transition
  changed_at      TIMESTAMPTZ,                    -- when the most recent transition happened
  -- Reserved for the deferred fork-a-new-version-on-editing-APPROVED mechanism (D17) —
  -- always 1 until that is built; editing an APPROVED requirement is refused today
  -- exactly as before this column existed.
  version         INT  NOT NULL DEFAULT 1,
  UNIQUE (tenant_id, key)
);
CREATE INDEX ON requirement (tenant_id, capability_id, status);
CREATE INDEX ON requirement (tenant_id, owner_id) WHERE deleted_at IS NULL;
CREATE INDEX ON requirement USING gin (statement gin_trgm_ops);

-- Immutable. One row per save. Never updated, never deleted.
CREATE TABLE requirement_revision (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      UUID NOT NULL REFERENCES tenant(id),
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
  tenant_id      UUID NOT NULL REFERENCES tenant(id),
  requirement_id UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  ordinal        SMALLINT NOT NULL,
  text           TEXT NOT NULL,
  UNIQUE (requirement_id, ordinal)
);

CREATE TABLE requirement_comment (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      UUID NOT NULL REFERENCES tenant(id),
  requirement_id UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  author_id      UUID NOT NULL REFERENCES app_user(id),
  body           TEXT NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE attachment (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      UUID NOT NULL REFERENCES tenant(id),
  requirement_id UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  filename       TEXT NOT NULL,
  current_version SMALLINT NOT NULL DEFAULT 1
);

-- Versions are never overwritten (prototype behaviour, kept)
CREATE TABLE attachment_version (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     UUID NOT NULL REFERENCES tenant(id),
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
  tenant_id      UUID NOT NULL REFERENCES tenant(id),
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
CREATE INDEX ON clarification (tenant_id, state) WHERE state = 'OPEN';

-- =====================================================================
-- TRACE GRAPH
-- =====================================================================

CREATE TABLE trace_link (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id  UUID NOT NULL REFERENCES tenant(id),
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
  UNIQUE (tenant_id, from_type, from_id, to_type, to_id, link_type)
);
CREATE INDEX ON trace_link (tenant_id, from_type, from_id);
CREATE INDEX ON trace_link (tenant_id, to_type,   to_id);

-- Materialised reachability for hot read paths. Rebuildable from trace_link.
CREATE TABLE trace_closure (
  tenant_id       UUID NOT NULL REFERENCES tenant(id),
  ancestor_type   TEXT NOT NULL,
  ancestor_id     UUID NOT NULL,
  descendant_type TEXT NOT NULL,
  descendant_id   UUID NOT NULL,
  depth           SMALLINT NOT NULL,
  PRIMARY KEY (tenant_id, ancestor_type, ancestor_id, descendant_type, descendant_id)
);

-- =====================================================================
-- DESIGN
-- =====================================================================

CREATE TABLE design_flow (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      UUID NOT NULL REFERENCES tenant(id),
  application_id UUID NOT NULL REFERENCES application(id) ON DELETE CASCADE,
  UNIQUE (tenant_id, application_id)
);

CREATE TABLE design_node (
  id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  flow_id  UUID NOT NULL REFERENCES design_flow(id) ON DELETE CASCADE,
  kind     TEXT NOT NULL CHECK (kind IN ('start','step','decision','integration','end')),
  label    TEXT NOT NULL,
  note     TEXT
);

CREATE TABLE design_edge (
  id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  flow_id   UUID NOT NULL REFERENCES design_flow(id) ON DELETE CASCADE,
  from_node UUID NOT NULL REFERENCES design_node(id) ON DELETE CASCADE,
  to_node   UUID NOT NULL REFERENCES design_node(id) ON DELETE CASCADE,
  label     TEXT,
  UNIQUE (from_node, to_node)
);

CREATE TABLE design_node_requirement (
  tenant_id      UUID NOT NULL REFERENCES tenant(id),
  node_id        UUID NOT NULL REFERENCES design_node(id) ON DELETE CASCADE,
  requirement_id UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  PRIMARY KEY (node_id, requirement_id)
);

-- =====================================================================
-- DETECTION
-- =====================================================================

CREATE TABLE gap_rule (
  tenant_id  UUID NOT NULL REFERENCES tenant(id),
  rule_key   TEXT NOT NULL REFERENCES gap_rule_template(key),
  enabled    BOOLEAN NOT NULL DEFAULT true,
  threshold  NUMERIC(4,3),
  config     JSONB,
  PRIMARY KEY (tenant_id, rule_key)
);

CREATE TABLE finding (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    UUID NOT NULL REFERENCES tenant(id),
  rule_key     TEXT NOT NULL REFERENCES gap_rule_template(key),
  fingerprint  TEXT NOT NULL,                -- stable identity across runs
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
  resolved_at   TIMESTAMPTZ,
  UNIQUE (tenant_id, fingerprint)
);
CREATE INDEX ON finding (tenant_id, rule_key, severity) WHERE state = 'OPEN';

CREATE TABLE requirement_embedding (
  requirement_id UUID PRIMARY KEY REFERENCES requirement(id) ON DELETE CASCADE,
  tenant_id      UUID NOT NULL REFERENCES tenant(id),
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
  tenant_id   UUID NOT NULL REFERENCES tenant(id),
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
  tenant_id   UUID NOT NULL REFERENCES tenant(id),
  key         TEXT NOT NULL,
  title       TEXT NOT NULL,
  UNIQUE (tenant_id, key)
);

CREATE TABLE test_run (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant(id),
  build_label TEXT,
  started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  source      TEXT                            -- CI system identifier
);

-- Verification is bound to the revision that was actually tested.
CREATE TABLE verification (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id            UUID NOT NULL REFERENCES tenant(id),
  requirement_id       UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  requirement_revision INT  NOT NULL,
  test_case_id         UUID REFERENCES test_case(id),
  test_run_id          UUID REFERENCES test_run(id),
  result               TEXT NOT NULL CHECK (result IN ('PASS','FAIL')),
  verified_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON verification (tenant_id, requirement_id, requirement_revision);

CREATE TABLE defect (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      UUID NOT NULL REFERENCES tenant(id),
  key            TEXT NOT NULL,
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
  raised_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, key)
);

-- =====================================================================
-- DELIVERY
-- =====================================================================

CREATE TABLE brief (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      UUID NOT NULL REFERENCES tenant(id),
  application_id UUID NOT NULL REFERENCES application(id),
  target         TEXT NOT NULL CHECK (target IN ('CLAUDE_CODE','CODEX','CURSOR','HUMAN')),
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
  tenant_id  UUID NOT NULL REFERENCES tenant(id),
  name       TEXT NOT NULL,
  state      TEXT NOT NULL DEFAULT 'PLANNED'
             CHECK (state IN ('PLANNED','OPEN','FROZEN','RELEASED')),
  UNIQUE (tenant_id, name)
);

CREATE TABLE release_scope_item (
  release_id     UUID NOT NULL REFERENCES release(id) ON DELETE CASCADE,
  requirement_id UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  PRIMARY KEY (release_id, requirement_id)
);

CREATE TABLE scope_movement (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      UUID NOT NULL REFERENCES tenant(id),
  release_id     UUID NOT NULL REFERENCES release(id) ON DELETE CASCADE,
  requirement_id UUID NOT NULL REFERENCES requirement(id),
  direction      TEXT NOT NULL CHECK (direction IN ('IN','OUT')),
  reason         TEXT,
  moved_by       UUID REFERENCES app_user(id),
  moved_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE baseline (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    UUID NOT NULL REFERENCES tenant(id),
  name         TEXT NOT NULL,
  release_id   UUID REFERENCES release(id),
  frozen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  frozen_by    UUID REFERENCES app_user(id),
  gaps_at_freeze INT NOT NULL DEFAULT 0,
  UNIQUE (tenant_id, name)
);

CREATE TABLE baseline_item (
  baseline_id    UUID NOT NULL REFERENCES baseline(id) ON DELETE CASCADE,
  requirement_id UUID NOT NULL REFERENCES requirement(id),
  revision       INT NOT NULL,
  PRIMARY KEY (baseline_id, requirement_id)
);

CREATE TABLE variant (
  id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  name      TEXT NOT NULL,
  UNIQUE (tenant_id, name)
);

CREATE TABLE variant_applicability (
  variant_id     UUID NOT NULL REFERENCES variant(id) ON DELETE CASCADE,
  requirement_id UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  applies        BOOLEAN NOT NULL DEFAULT true,
  PRIMARY KEY (variant_id, requirement_id)
);

CREATE TABLE environment (
  id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  name      TEXT NOT NULL,
  ordinal   SMALLINT NOT NULL DEFAULT 0,
  UNIQUE (tenant_id, name)
);

CREATE TABLE deployment (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      UUID NOT NULL REFERENCES tenant(id),
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
  tenant_id   UUID NOT NULL REFERENCES tenant(id),
  key         TEXT NOT NULL,
  title       TEXT NOT NULL,
  product_id  UUID REFERENCES product(id),
  revision    INT NOT NULL DEFAULT 1,
  state       TEXT NOT NULL DEFAULT 'DRAFT',
  UNIQUE (tenant_id, key)
);

CREATE TABLE import_batch (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    UUID NOT NULL REFERENCES tenant(id),
  filename     TEXT NOT NULL,
  application_id UUID REFERENCES application(id),
  upload_kind  TEXT NOT NULL DEFAULT 'FREEFORM'
               CHECK (upload_kind IN ('FREEFORM','STANDARD_SPEC','REQIF','EXCEL','WORD','PRD_TEMPLATE')),
  uploaded_by  UUID REFERENCES app_user(id),
  uploaded_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  state        TEXT NOT NULL DEFAULT 'PARSED'
);

CREATE TABLE import_candidate (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     UUID NOT NULL REFERENCES tenant(id),
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
  tenant_id   UUID NOT NULL REFERENCES tenant(id),
  key         TEXT NOT NULL,
  title       TEXT NOT NULL,
  raised_by   UUID REFERENCES app_user(id),
  state       TEXT NOT NULL DEFAULT 'OPEN'
              CHECK (state IN ('OPEN','APPROVED','REJECTED','APPLIED')),
  impact_requirements INT NOT NULL DEFAULT 0,
  impact_apps         INT NOT NULL DEFAULT 0,
  decided_by  UUID REFERENCES app_user(id),
  decided_at  TIMESTAMPTZ,
  UNIQUE (tenant_id, key)
);

-- =====================================================================
-- PLATFORM SUPPORT
-- =====================================================================

CREATE TABLE audit_event (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant(id),
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
CREATE INDEX ON audit_event (tenant_id, occurred_at DESC);

-- Append-only enforcement
CREATE OR REPLACE FUNCTION audit_is_append_only() RETURNS TRIGGER AS $fn$
BEGIN
  RAISE EXCEPTION 'audit_event is append-only';
END;
$fn$ LANGUAGE plpgsql;

CREATE TRIGGER audit_no_update BEFORE UPDATE OR DELETE ON audit_event
  FOR EACH ROW EXECUTE FUNCTION audit_is_append_only();

-- Deliberately NOT tenant-FK'd and deliberately EXCLUDED from RLS below:
-- the relay process must read across every tenant to publish. It runs as a
-- dedicated role (vyoog_relay) that has access to this table and nothing else.
CREATE TABLE outbox_event (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id    UUID NOT NULL,
  event_type   TEXT NOT NULL,
  payload      JSONB NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at TIMESTAMPTZ
);
CREATE INDEX ON outbox_event (published_at) WHERE published_at IS NULL;

CREATE TABLE notification (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id  UUID NOT NULL REFERENCES tenant(id),
  user_id    UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  tone       TEXT NOT NULL DEFAULT 'info',
  title      TEXT NOT NULL,
  subtitle   TEXT,
  link       TEXT,
  read_at    TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE integration_connection (
  tenant_id  UUID NOT NULL REFERENCES tenant(id),
  key        TEXT NOT NULL,                  -- 'git' | 'jira' | 'ci' | 'hr' | 'planning'
  connected  BOOLEAN NOT NULL DEFAULT false,
  config     JSONB,
  PRIMARY KEY (tenant_id, key)
);

-- =====================================================================
-- DERIVED VIEWS
-- =====================================================================

-- Principle 5: verification is a predicate, never a stored flag.
CREATE VIEW requirement_verification_state AS
SELECT r.id, r.tenant_id,
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
SELECT r.id, r.tenant_id,
  EXISTS (SELECT 1 FROM trace_link t WHERE t.to_type='REQUIREMENT' AND t.to_id=r.id
            AND t.link_type IN ('SATISFIES','DERIVES'))   AS has_upstream,
  EXISTS (SELECT 1 FROM design_node_requirement d WHERE d.requirement_id=r.id) AS has_design,
  EXISTS (SELECT 1 FROM trace_link t WHERE t.from_type='CODE' AND t.to_id=r.id) AS has_code,
  EXISTS (SELECT 1 FROM verification v WHERE v.requirement_id=r.id
            AND v.requirement_revision=r.revision AND v.result='PASS')          AS has_test
FROM requirement r;

-- =====================================================================
-- ROW LEVEL SECURITY — applied uniformly so none is forgotten
-- =====================================================================

DO $rls$
DECLARE t TEXT;
BEGIN
  FOR t IN
    SELECT c.relname
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      JOIN pg_attribute a ON a.attrelid = c.oid
     WHERE n.nspname = 'public'
       AND c.relkind = 'r'
       AND a.attname = 'tenant_id'
       -- outbox_event is cross-tenant by design; the relay could not read it
       -- under RLS. It is protected by role grants instead.
       AND c.relname NOT IN ('tenant', 'outbox_event')
  LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE  ROW LEVEL SECURITY', t);
    EXECUTE format($p$
      CREATE POLICY tenant_isolation ON %I
        USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
        WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid)
    $p$, t);
  END LOOP;
END
$rls$;

-- GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO vyoog_app;
-- GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO vyoog_app;
--
-- The relay role sees the outbox and nothing else:
-- CREATE ROLE vyoog_relay LOGIN PASSWORD '...';
-- REVOKE ALL ON ALL TABLES IN SCHEMA public FROM vyoog_relay;
-- GRANT SELECT, UPDATE (published_at) ON outbox_event TO vyoog_relay;

-- =====================================================================
-- THE MOST IMPORTANT TEST IN THE SUITE
-- With no app.tenant_id set, every tenant-scoped query must return zero
-- rows -- not all rows.  Assert this in CI.
--
--   RESET app.tenant_id;
--   SELECT count(*) FROM requirement;   -- must be 0
-- =====================================================================
