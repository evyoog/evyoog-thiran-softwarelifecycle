-- VYB-0827: persists whether an accepted test case was an INDIVIDUAL suggestion (tests
-- the requirement alone) or a DEPENDENCY one (tests it together with something it's
-- trace-linked to) — previously only known transiently, on the AI suggestion, and lost
-- once accepted. Nullable and forward-only: every existing row (CI-ingested, manually
-- drafted before this column existed) genuinely has no category, and none is invented.
ALTER TABLE test_case ADD COLUMN category TEXT CHECK (category IN ('INDIVIDUAL', 'DEPENDENCY'));
