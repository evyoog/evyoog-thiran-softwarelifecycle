-- =====================================================================
-- Phase 4 — intelligence, session 11.
--
-- V001's baseline already had requirement_embedding (with its pgvector HNSW
-- index), the finding table's confidence/model columns, and the whole
-- import_batch/import_candidate pair. This adds only what genuinely didn't
-- exist: a table for control clauses (TraceObjectType.CLAUSE had "no backing
-- table" until now), the handful of import_candidate columns the queue needs
-- to track editing/duplicates/capability-confirmation, and this deployment's
-- AI configuration.
-- =====================================================================

-- VYB-0611: a control clause a requirement can satisfy. No source system for
-- these exists yet, so clauses are entered directly — that's an intake gap,
-- not a detection one; the detector works on whatever rows exist here.
CREATE TABLE clause (
  id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  standard TEXT NOT NULL,
  section  TEXT,
  text     TEXT NOT NULL
);

-- VYB-0632/0635/0637/0664: the extraction pipeline needs to keep the original
-- text distinct from the (editable) statement, record where in the source
-- document a candidate came from, record why an override past a duplicate
-- flag was accepted, and gate commit on an explicit capability confirmation.
ALTER TABLE import_candidate
  ADD COLUMN original_text TEXT,
  ADD COLUMN source_location TEXT,
  ADD COLUMN import_reason TEXT,
  ADD COLUMN capability_confirmed BOOLEAN NOT NULL DEFAULT false;

-- VYB-0630: the raw extracted text of the uploaded document. Kept as plain
-- text rather than routed through the S3-compatible attachment store (VYB-0123)
-- — that path is already disclosed as untested in this environment, and a
-- spec document is text either way (freeform/markdown/CSV/ReqIF XML).
ALTER TABLE import_batch ADD COLUMN raw_text TEXT;

-- VYB-0604/0618/0620: this deployment's one configuration row grows three
-- more settings — which embedding model is currently configured (a change
-- here is what "model change" in VYB-0604 actually means), the per-sweep
-- bound on AI-model calls, and the dismissal-rate ceiling above which a newly
-- provisioned tenant would get a detector disabled by default (VYB-0618 —
-- this deployment has no tenant-provisioning flow to hang that on, since it's
-- single-tenant by design; see BUILD-REGISTER.md for how that AC is adapted).
ALTER TABLE app_config
  ADD COLUMN embedding_model TEXT NOT NULL DEFAULT 'local-hashing-v1',
  ADD COLUMN ai_calls_per_run_limit INT NOT NULL DEFAULT 50,
  ADD COLUMN noisy_detector_dismissal_ceiling NUMERIC(4,3) NOT NULL DEFAULT 0.700;
