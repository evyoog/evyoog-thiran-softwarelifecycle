-- VYB-0926: a defect raised from a failed step of a manual run carries a link back to that step
-- (or, for a case that has no steps, to the run case). The test, the run, the expected and the
-- actual result are read through the link, so they stay accurate; no description column is added.
-- One defect per failed step: the unique indexes make that a database rule. If a run's rows are
-- ever removed the defect stays and the link is cleared.
ALTER TABLE defect ADD COLUMN raised_from_run_step_id UUID REFERENCES test_run_step(id) ON DELETE SET NULL;
ALTER TABLE defect ADD COLUMN raised_from_run_case_id UUID REFERENCES test_run_case(id) ON DELETE SET NULL;
ALTER TABLE defect ADD CONSTRAINT defect_raised_from_one_chk
  CHECK (raised_from_run_step_id IS NULL OR raised_from_run_case_id IS NULL);
CREATE UNIQUE INDEX defect_raised_from_run_step_uq ON defect (raised_from_run_step_id) WHERE raised_from_run_step_id IS NOT NULL;
CREATE UNIQUE INDEX defect_raised_from_run_case_uq ON defect (raised_from_run_case_id) WHERE raised_from_run_case_id IS NOT NULL;
