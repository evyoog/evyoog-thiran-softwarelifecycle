-- =====================================================================
-- Session 14: schema support for the features closed this session —
-- draft test cases, release planning dates (calendar), and provenance
-- on test cases (borrowed-signal labeling in Delivery).
-- =====================================================================

-- VYB-0363: draft test cases. Existing rows (all CI-ingested, via
-- POST /ci/test-runs) default to INGESTED — that's accurate for every row that
-- already exists, since ingestion is the only path that has ever created one until
-- now. New human-drafted test cases (the feature this column exists for) are created
-- with status='DRAFT' explicitly by TestCaseService.
ALTER TABLE test_case ADD COLUMN status TEXT NOT NULL DEFAULT 'INGESTED'
  CHECK (status IN ('DRAFT', 'INGESTED'));
ALTER TABLE test_case ADD COLUMN created_by UUID REFERENCES app_user(id);
ALTER TABLE test_case ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- A drafted test case is raised by a person through Vyoog itself, not reported by a
-- CI system that already has its own key — same reasoning as defect_key_seq (V005).
CREATE SEQUENCE test_case_key_seq START 1;

-- VYB-0372: the only genuinely plannable date this application has ever had a real
-- concept of — when a release is targeted to ship. Nullable: not every release has a
-- target yet, and nothing should invent one.
ALTER TABLE release ADD COLUMN target_date TIMESTAMPTZ;
