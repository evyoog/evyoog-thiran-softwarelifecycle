-- VYB-0802: inserts REVIEWED as a manual review gate between IN_REVIEW and APPROVED.
-- Forward-only: the CHECK is dropped and recreated with the sixth value, which is the
-- only way to widen a CHECK in place (same technique as V017/V024).
--
-- Every existing row already satisfies the new constraint (it is a strict superset of
-- the old one), so this needs no backfill and cannot fail on populated data.
ALTER TABLE requirement DROP CONSTRAINT requirement_status_check;
ALTER TABLE requirement ADD CONSTRAINT requirement_status_check
  CHECK (status IN ('DRAFT', 'IN_REVIEW', 'REVIEWED', 'APPROVED', 'VERIFIED', 'REJECTED'));
