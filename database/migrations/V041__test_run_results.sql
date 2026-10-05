-- VYB-0924a: executing a manual run. A result is recorded on each step of the run's
-- snapshot (test_run_step), with the actual result; a case that has no steps is judged
-- on the case itself (test_run_case). The case result is derived from its steps, never
-- stored, so it cannot disagree with them. Evidence attachments and retest are VYB-0924b;
-- verification records are VYB-0925. Nothing here writes verification or requirement.status.

ALTER TABLE test_run_step ADD COLUMN result TEXT CHECK (result IN ('PASS', 'FAIL', 'BLOCKED'));
ALTER TABLE test_run_step ADD COLUMN actual_result TEXT;
ALTER TABLE test_run_step ADD COLUMN executed_by UUID REFERENCES app_user(id);
ALTER TABLE test_run_step ADD COLUMN executed_at TIMESTAMPTZ;
ALTER TABLE test_run_step ADD CONSTRAINT test_run_step_recorded_chk
  CHECK ((result IS NULL) = (executed_at IS NULL) AND (result IS NULL) = (executed_by IS NULL));
-- What happened is required exactly when the step did not pass.
ALTER TABLE test_run_step ADD CONSTRAINT test_run_step_actual_chk
  CHECK (result IS NULL OR result = 'PASS' OR btrim(coalesce(actual_result, '')) <> '');

ALTER TABLE test_run_case ADD COLUMN result TEXT CHECK (result IN ('PASS', 'FAIL', 'BLOCKED'));
ALTER TABLE test_run_case ADD COLUMN actual_result TEXT;
ALTER TABLE test_run_case ADD COLUMN executed_by UUID REFERENCES app_user(id);
ALTER TABLE test_run_case ADD COLUMN executed_at TIMESTAMPTZ;
ALTER TABLE test_run_case ADD CONSTRAINT test_run_case_recorded_chk
  CHECK ((result IS NULL) = (executed_at IS NULL) AND (result IS NULL) = (executed_by IS NULL));
ALTER TABLE test_run_case ADD CONSTRAINT test_run_case_actual_chk
  CHECK (result IS NULL OR result = 'PASS' OR btrim(coalesce(actual_result, '')) <> '');
