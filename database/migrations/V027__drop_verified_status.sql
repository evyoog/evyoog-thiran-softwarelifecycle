-- VYB-0810: VERIFIED is removed as a requirement status. It used to be set only by
-- VerificationService on a passing CI test at the current revision (Principle 3) — that
-- test-evidence system stays (the verification, test_case and test_run tables are
-- untouched, still recorded for quality reporting), but it no longer touches
-- requirement.status. Verifying became a decision a person makes from the "Verify"
-- action on an IN_REVIEW requirement, landing on REVIEWED, not something CI reports
-- back automatically. APPROVED is now the terminal status of this state machine.
--
-- Backfill first, narrow the CHECK second: any row already sitting in VERIFIED becomes
-- APPROVED — the same status VYB-0802's Requirement#applyRevision already dropped it
-- back to on a content edit, now made permanent since there is nowhere else for it to
-- go. This must run before the CHECK is narrowed, or the narrower constraint would
-- refuse to attach against any row still in the old value.
UPDATE requirement SET status = 'APPROVED' WHERE status = 'VERIFIED';

ALTER TABLE requirement DROP CONSTRAINT requirement_status_check;
ALTER TABLE requirement ADD CONSTRAINT requirement_status_check
  CHECK (status IN ('DRAFT', 'IN_REVIEW', 'REVIEWED', 'APPROVED', 'REJECTED'));
