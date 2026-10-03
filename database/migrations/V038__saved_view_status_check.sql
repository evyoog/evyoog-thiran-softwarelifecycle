-- VYB-0911 (F22): saved_view.status must accept the statuses a requirement can actually have.
--
-- V019 copied the requirement status list as it stood then ('DRAFT','IN_REVIEW','APPROVED',
-- 'VERIFIED','REJECTED') and nothing updated it when the requirement table's CHECK changed
-- (V026 added REVIEWED, V027 dropped VERIFIED, V028 added NEEDS_REVISION). So a person could not
-- save a view of requirements awaiting a decision (REVIEWED) or sent back (NEEDS_REVISION), and the
-- table still accepted VERIFIED, which has not been a status since D16.
--
-- Backfill first, narrow the CHECK second, the same order and the same mapping V027 used on the
-- requirement table: a view that filtered on VERIFIED now filters on APPROVED, because that is where
-- VERIFIED requirements went. The CHECK would otherwise refuse to attach against such a row.
UPDATE saved_view SET status = 'APPROVED' WHERE status = 'VERIFIED';

ALTER TABLE saved_view DROP CONSTRAINT saved_view_status_check;
ALTER TABLE saved_view ADD CONSTRAINT saved_view_status_check
  CHECK (status IN ('DRAFT', 'IN_REVIEW', 'REVIEWED', 'NEEDS_REVISION', 'APPROVED', 'REJECTED'));
