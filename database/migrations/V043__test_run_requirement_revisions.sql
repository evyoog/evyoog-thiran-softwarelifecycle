-- VYB-0925: verification records from a manual run, bound to the requirement revision.
--
-- When a run is started, the requirements each of its cases verifies (the TEST --VERIFIES--> REQUIREMENT
-- links) and each requirement's revision at that moment are frozen here. Completing the run writes its
-- verification rows from this table, so the evidence is for the text the tester actually saw: if the
-- requirement is edited during the run, the result is stale straight away (the existing
-- requirement_verification_state predicate compares verification.requirement_revision with the current one).
CREATE TABLE test_run_case_requirement (
  run_case_id          UUID NOT NULL REFERENCES test_run_case(id) ON DELETE CASCADE,
  requirement_id       UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  requirement_revision INT  NOT NULL,
  PRIMARY KEY (run_case_id, requirement_id)
);
CREATE INDEX test_run_case_requirement_requirement_id_idx ON test_run_case_requirement (requirement_id);
