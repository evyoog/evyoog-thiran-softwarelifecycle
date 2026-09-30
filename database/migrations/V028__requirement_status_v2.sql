-- VYB-0813 (D17): a full replacement of the requirement lifecycle's status machine.
-- NEEDS_REVISION is a new status; every existing status value (DRAFT, IN_REVIEW,
-- REVIEWED, APPROVED, REJECTED) is unchanged and needs no relabeling or backfill of
-- its own — this migration only widens what the column accepts and adds the columns
-- the new transition metadata needs.
--
-- previous_status/reason/changed_by/changed_at are populated going forward by
-- Requirement#transitionTo on every transition; for rows that predate this migration,
-- changed_by/changed_at are best-effort backfilled from updated_by/updated_at (the
-- closest existing record of "who/when this last changed") rather than left NULL for
-- a row that has in fact been touched before. previous_status/reason stay NULL for
-- those rows — there is no reliable prior status to infer for them.

ALTER TABLE requirement
  ADD COLUMN previous_status TEXT,
  ADD COLUMN revision_count  INT  NOT NULL DEFAULT 0,
  ADD COLUMN reason          TEXT,
  ADD COLUMN changed_by      UUID REFERENCES app_user(id),
  ADD COLUMN changed_at      TIMESTAMPTZ,
  ADD COLUMN version         INT  NOT NULL DEFAULT 1;

UPDATE requirement SET changed_by = updated_by, changed_at = updated_at WHERE changed_at IS NULL;

ALTER TABLE requirement DROP CONSTRAINT requirement_status_check;
ALTER TABLE requirement ADD CONSTRAINT requirement_status_check
  CHECK (status IN ('DRAFT', 'IN_REVIEW', 'REVIEWED', 'NEEDS_REVISION', 'APPROVED', 'REJECTED'));
