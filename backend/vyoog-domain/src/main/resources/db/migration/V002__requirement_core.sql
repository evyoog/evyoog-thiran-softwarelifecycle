-- =====================================================================
-- Phase 1 — requirement core, session 3.
--
-- Forward-only: V001 already shipped (Phase 0), so schema changes for the
-- register land here rather than editing the baseline in place.
-- =====================================================================

-- VYB-0101 AC1: archive is soft and preserves references.
ALTER TABLE product     ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE application ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE capability  ADD COLUMN archived_at TIMESTAMPTZ;

-- VYB-0111: atomic key allocation, no gap-filling reuse. A sequence gives us
-- atomicity for free; gaps from a rolled-back transaction are expected and fine.
CREATE SEQUENCE requirement_key_seq START WITH 1;
