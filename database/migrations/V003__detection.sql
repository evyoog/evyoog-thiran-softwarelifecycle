-- =====================================================================
-- Phase 1 — detection, session 5.
-- =====================================================================

-- VYB-0154: "keep a finding dismissed until the underlying object's revision
-- changes" needs something to compare against. Recorded at first-seen and refreshed
-- on every reconcile so a later run can tell whether the object actually moved.
ALTER TABLE finding ADD COLUMN object_revision INT;
