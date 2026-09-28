-- VYB-0824: richer test-case authoring (manual and AI-proposed) needs more than a
-- title. Nullable and forward-only: no existing row (CI-ingested via V001, or the
-- session-14 DRAFT rows added by V009) has a description, and none should be invented
-- for it.
ALTER TABLE test_case ADD COLUMN description TEXT;
