-- =====================================================================
-- VYB-0630 defect fix: import_batch.state's default was 'PARSED', a value
-- no code path ever legitimately produces — ImportService#extractCandidates
-- only ever writes 'EXTRACTED'. The Import Queue screen's "Extract
-- candidates" button gates on state = 'UPLOADED' (ImportQueue.tsx), so
-- every freshly uploaded batch landed on a state the UI never recognised
-- as needing extraction, and the button that starts extraction never
-- rendered. Forward-only: V001's column default is left as it was applied;
-- this migration corrects it going forward and repairs rows already
-- affected.
-- =====================================================================

ALTER TABLE import_batch ALTER COLUMN state SET DEFAULT 'UPLOADED';

-- Repairs existing batches stuck at the old buggy default. Safe precisely
-- because no code path ever sets 'PARSED' deliberately — every row bearing
-- it got there via the wrong default, uploaded but never extracted, with
-- zero import_candidate rows to lose.
UPDATE import_batch SET state = 'UPLOADED' WHERE state = 'PARSED';
