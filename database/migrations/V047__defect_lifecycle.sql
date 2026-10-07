-- VYB-0931: the defect lifecycle (fix, close, reopen, edit, assign, comment, link to a test, run and release).
--
-- defect.state already has OPEN / FIXED / CLOSED (V001); until now only OPEN and CLOSED were reachable. Every move is
-- now a recorded row (defect_transition), a defect can carry one explicit link each to a test case, a test run and a
-- release (all optional; the test and run of a defect raised from a failed step are also derivable from V044's link),
-- and people can comment on it. Comments are append-only: there is no edit and no delete.

ALTER TABLE defect ADD COLUMN test_case_id UUID REFERENCES test_case(id) ON DELETE SET NULL;
ALTER TABLE defect ADD COLUMN test_run_id  UUID REFERENCES test_run(id)  ON DELETE SET NULL;
ALTER TABLE defect ADD COLUMN release_id   UUID REFERENCES release(id)   ON DELETE SET NULL;
CREATE INDEX defect_test_case_id_idx ON defect (test_case_id) WHERE test_case_id IS NOT NULL;
CREATE INDEX defect_test_run_id_idx  ON defect (test_run_id)  WHERE test_run_id  IS NOT NULL;
CREATE INDEX defect_release_id_idx   ON defect (release_id)   WHERE release_id   IS NOT NULL;
CREATE INDEX defect_state_idx ON defect (state);

CREATE TABLE defect_transition (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  defect_id  UUID NOT NULL REFERENCES defect(id) ON DELETE CASCADE,
  from_state TEXT NOT NULL CHECK (from_state IN ('OPEN','FIXED','CLOSED')),
  to_state   TEXT NOT NULL CHECK (to_state   IN ('OPEN','FIXED','CLOSED')),
  reason     TEXT,
  changed_by UUID REFERENCES app_user(id),
  changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (from_state <> to_state),
  -- a reopen always says why
  CHECK (to_state <> 'OPEN' OR btrim(coalesce(reason, '')) <> '')
);
CREATE INDEX defect_transition_defect_id_idx ON defect_transition (defect_id, changed_at);

CREATE TABLE defect_comment (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  defect_id  UUID NOT NULL REFERENCES defect(id) ON DELETE CASCADE,
  author_id  UUID REFERENCES app_user(id),
  body       TEXT NOT NULL CHECK (btrim(body) <> '' AND char_length(body) <= 4000),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX defect_comment_defect_id_idx ON defect_comment (defect_id, created_at);

CREATE FUNCTION defect_comment_is_append_only() RETURNS TRIGGER AS $fn$
BEGIN
  RAISE EXCEPTION 'defect_comment is append-only';
END;
$fn$ LANGUAGE plpgsql;
CREATE TRIGGER defect_comment_no_update BEFORE UPDATE OR DELETE ON defect_comment
  FOR EACH ROW EXECUTE FUNCTION defect_comment_is_append_only();
