-- =====================================================================
-- VYB-0667: multi-agent analysis of an imported document, and the
-- description it proposes.
--
-- One row per run, never overwritten: a re-analysis after the model or
-- the prompt version changed is a different answer to the same question,
-- and the earlier row is what an earlier reviewer's decision was made
-- against. `state` starts PROPOSED and only a person moves it — nothing
-- in the pipeline writes ACCEPTED (Principle 7, VYB-0619).
--
-- chunks_analysed < chunks_total means the per-run AI budget
-- (app_config.ai_calls_per_run_limit, VYB-0620) stopped the run early;
-- the row records that rather than presenting partial coverage as whole.
-- findings_rejected counts findings dropped because their quoted
-- evidence did not occur in the source text — the anti-fabrication check
-- runs in DocumentAnalysisService, not in the prompt.
-- =====================================================================

CREATE TABLE import_document_analysis (
  id                     UUID PRIMARY KEY,
  batch_id               UUID NOT NULL REFERENCES import_batch(id) ON DELETE CASCADE,
  state                  TEXT NOT NULL DEFAULT 'PROPOSED'
                           CHECK (state IN ('PROPOSED', 'ACCEPTED', 'DISMISSED')),
  description            TEXT NOT NULL,
  findings               JSONB NOT NULL,
  themes                 JSONB,
  unsupported_claims     JSONB,
  chunks_total           INTEGER NOT NULL,
  chunks_analysed        INTEGER NOT NULL,
  findings_kept          INTEGER NOT NULL,
  findings_rejected      INTEGER NOT NULL,
  noise_blocks_discarded INTEGER NOT NULL,
  revision_ran           BOOLEAN NOT NULL DEFAULT false,
  model                  TEXT NOT NULL,
  prompt_version         TEXT NOT NULL,
  ai_calls               INTEGER NOT NULL,
  created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  decided_at             TIMESTAMPTZ,
  decided_by             UUID REFERENCES app_user(id),
  dismiss_reason         TEXT,

  -- A decision is a person and a time together; neither alone is a decision.
  CONSTRAINT import_document_analysis_decision_complete
    CHECK ((state = 'PROPOSED' AND decided_at IS NULL AND decided_by IS NULL)
        OR (state <> 'PROPOSED' AND decided_at IS NOT NULL)),
  -- VYB-0619: a dismissal carries its reason.
  CONSTRAINT import_document_analysis_dismissal_has_reason
    CHECK (state <> 'DISMISSED' OR (dismiss_reason IS NOT NULL AND length(trim(dismiss_reason)) > 0))
);

CREATE INDEX idx_document_analysis_batch ON import_document_analysis (batch_id, created_at DESC);
