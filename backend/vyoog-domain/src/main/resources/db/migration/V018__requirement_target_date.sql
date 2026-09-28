-- D10: a requirement may carry its own target date — the date somebody committed to,
-- as opposed to the date its stage threshold implies. Nullable on purpose: most
-- requirements will never have one, and the Planning screen falls back to
-- app_config.stage_stall_threshold_days for those rather than inventing a date.
--
-- Not an effort field. Nothing here records how long work takes; §13 of the build
-- specification still rules that out, and no column added here is read by any
-- estimate, capacity or velocity calculation, because none exists.
ALTER TABLE requirement ADD COLUMN target_date DATE;

-- The Planning board sorts and buckets by this, always filtered to live rows.
CREATE INDEX idx_requirement_target_date ON requirement (target_date) WHERE deleted_at IS NULL;
