-- VYB-0923: test plan, suite and run entities; structured steps and expected results.
--
-- A test plan belongs to an application (optionally a release) and holds named, ordered
-- suites; a suite is an ordered group of EXISTING test cases. A run is the execution of one
-- suite and copies the suite's cases and their steps when it is created, so editing a case
-- afterwards never rewrites what was run. test_run is extended, not replaced: every existing
-- row is a CI run and stays one (kind = 'CI', status = 'COMPLETED'), so CI ingestion
-- (POST /ci/test-runs) is untouched. Per-step results are VYB-0924 and are not columns here.

CREATE SEQUENCE test_plan_key_seq START 1;

CREATE TABLE test_plan (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  key            TEXT NOT NULL UNIQUE,
  name           TEXT NOT NULL CHECK (btrim(name) <> ''),
  description    TEXT,
  application_id UUID NOT NULL REFERENCES application(id) ON DELETE CASCADE,
  release_id     UUID REFERENCES release(id) ON DELETE SET NULL,
  created_by     UUID REFERENCES app_user(id),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX test_plan_application_id_idx ON test_plan (application_id);
CREATE INDEX test_plan_release_id_idx ON test_plan (release_id) WHERE release_id IS NOT NULL;

CREATE TABLE test_suite (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  plan_id     UUID NOT NULL REFERENCES test_plan(id) ON DELETE CASCADE,
  name        TEXT NOT NULL CHECK (btrim(name) <> ''),
  description TEXT,
  position    INT  NOT NULL CHECK (position >= 1),
  UNIQUE (plan_id, name),
  UNIQUE (plan_id, position) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE test_suite_case (
  suite_id     UUID NOT NULL REFERENCES test_suite(id) ON DELETE CASCADE,
  test_case_id UUID NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
  position     INT  NOT NULL CHECK (position >= 1),
  PRIMARY KEY (suite_id, test_case_id),
  UNIQUE (suite_id, position) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX test_suite_case_test_case_id_idx ON test_suite_case (test_case_id);

-- Structured steps beside test_case.description, which is untouched and migrates nothing.
CREATE TABLE test_step (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  test_case_id    UUID NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
  position        INT  NOT NULL CHECK (position >= 1),
  action          TEXT NOT NULL CHECK (btrim(action) <> ''),
  expected_result TEXT NOT NULL CHECK (btrim(expected_result) <> ''),
  UNIQUE (test_case_id, position) DEFERRABLE INITIALLY DEFERRED
);

-- test_run: CI rows keep working; MANUAL rows are runs of a suite.
ALTER TABLE test_run ADD COLUMN kind TEXT NOT NULL DEFAULT 'CI'
  CHECK (kind IN ('CI', 'MANUAL'));
ALTER TABLE test_run ADD COLUMN status TEXT NOT NULL DEFAULT 'COMPLETED'
  CHECK (status IN ('PLANNED', 'IN_PROGRESS', 'COMPLETED'));
-- No ON DELETE action: a suite that has been run cannot be deleted from under its runs.
ALTER TABLE test_run ADD COLUMN suite_id UUID REFERENCES test_suite(id);
ALTER TABLE test_run ADD COLUMN assigned_to UUID REFERENCES app_user(id);
ALTER TABLE test_run ADD COLUMN created_by UUID REFERENCES app_user(id);
ALTER TABLE test_run ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE test_run ADD COLUMN completed_at TIMESTAMPTZ;
-- A planned run has not started: NULL until it does. CI rows keep the value they have.
ALTER TABLE test_run ALTER COLUMN started_at DROP NOT NULL;
ALTER TABLE test_run ALTER COLUMN started_at DROP DEFAULT;
UPDATE test_run SET created_at = started_at WHERE started_at IS NOT NULL;
ALTER TABLE test_run ADD CONSTRAINT test_run_manual_has_suite_chk
  CHECK (kind = 'CI' OR suite_id IS NOT NULL);
ALTER TABLE test_run ADD CONSTRAINT test_run_ci_is_completed_chk
  CHECK (kind = 'MANUAL' OR status = 'COMPLETED');
CREATE INDEX test_run_suite_id_idx ON test_run (suite_id) WHERE suite_id IS NOT NULL;
CREATE INDEX test_run_assigned_to_idx ON test_run (assigned_to) WHERE assigned_to IS NOT NULL;

-- The snapshot taken when a run is created. Copies, not references: no FK to the live case
-- or step, so a later edit or deletion of either changes nothing here.
CREATE TABLE test_run_case (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  run_id       UUID NOT NULL REFERENCES test_run(id) ON DELETE CASCADE,
  position     INT  NOT NULL CHECK (position >= 1),
  test_case_id UUID,                      -- the case it was copied from; deliberately not an FK
  case_key     TEXT NOT NULL,
  title        TEXT NOT NULL,
  description  TEXT,
  UNIQUE (run_id, position) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE test_run_step (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  run_case_id UUID NOT NULL REFERENCES test_run_case(id) ON DELETE CASCADE,
  position    INT  NOT NULL CHECK (position >= 1),
  action          TEXT NOT NULL,
  expected_result TEXT NOT NULL,
  UNIQUE (run_case_id, position) DEFERRABLE INITIALLY DEFERRED
);
