-- VYB-0924b: evidence on a run's results, and retest.
--
-- Evidence reuses the requirement attachment store (the product owner's choice): the file is a
-- normal attachment of a requirement the test case verifies, and this table links one exact
-- attachment version to a step (or, for a case with no steps, to the case) of a run. The link
-- points at the version, not the attachment, so re-uploading the same filename later can never
-- change what an earlier result was backed by. If the requirement is deleted its attachments go
-- with it, and so do these links.
CREATE TABLE test_run_evidence (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  run_step_id           UUID REFERENCES test_run_step(id) ON DELETE CASCADE,
  run_case_id           UUID REFERENCES test_run_case(id) ON DELETE CASCADE,
  attachment_version_id UUID NOT NULL REFERENCES attachment_version(id) ON DELETE CASCADE,
  added_by              UUID REFERENCES app_user(id),
  added_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK ((run_step_id IS NULL) <> (run_case_id IS NULL))
);
CREATE INDEX test_run_evidence_run_step_id_idx ON test_run_evidence (run_step_id) WHERE run_step_id IS NOT NULL;
CREATE INDEX test_run_evidence_run_case_id_idx ON test_run_evidence (run_case_id) WHERE run_case_id IS NOT NULL;
CREATE INDEX test_run_evidence_attachment_version_id_idx ON test_run_evidence (attachment_version_id);

-- A retest is a new run of the failed or blocked cases of a completed run, linked back to it.
ALTER TABLE test_run ADD COLUMN retest_of UUID REFERENCES test_run(id);
CREATE INDEX test_run_retest_of_idx ON test_run (retest_of) WHERE retest_of IS NOT NULL;
ALTER TABLE test_run ADD CONSTRAINT test_run_retest_of_manual_chk CHECK (retest_of IS NULL OR kind = 'MANUAL');
