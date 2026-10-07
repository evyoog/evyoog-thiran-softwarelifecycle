-- VYB-0938 (F30): every AI proposal is a row here, and a person's decision on it is the only way its text reaches a
-- requirement, a test case or a brief. A proposal is recorded when the AI produces it (PENDING); a person accepts it
-- (optionally with edits, kept beside the original), rejects it, or it is superseded by a newer one for the same thing.
-- Kinds this row covers: REWRITE (a rewritten requirement statement), TEST_CASE (a suggested test case) and
-- BRIEF_ELABORATION (the extra detail for one requirement in a brief). Import candidates and document analysis findings
-- keep their own tables and move here in a later row.
CREATE TABLE ai_proposal (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  kind                 TEXT NOT NULL CHECK (kind IN ('REWRITE', 'TEST_CASE', 'BRIEF_ELABORATION')),
  state                TEXT NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'ACCEPTED', 'REJECTED', 'SUPERSEDED')),
  -- What it is about, and the revision it was made against, so a proposal for text that has since changed says so.
  -- A rewrite drafted for a requirement not yet created has neither.
  requirement_id       UUID REFERENCES requirement(id) ON DELETE CASCADE,
  requirement_revision INT,
  payload              JSONB NOT NULL,           -- what the AI proposed, never edited
  accepted_payload     JSONB,                    -- what was applied, when the person edited it first
  model                TEXT NOT NULL,
  proposed_by          UUID REFERENCES app_user(id),
  proposed_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  decided_by           UUID REFERENCES app_user(id),
  decided_at           TIMESTAMPTZ,
  decision_reason      TEXT,
  applied_type         TEXT CHECK (applied_type IN ('REQUIREMENT', 'TEST_CASE')),
  applied_id           UUID,
  CHECK ((requirement_id IS NULL) = (requirement_revision IS NULL)),
  CHECK (kind = 'REWRITE' OR requirement_id IS NOT NULL),
  CHECK ((state = 'PENDING' AND decided_at IS NULL AND decided_by IS NULL AND accepted_payload IS NULL AND applied_id IS NULL)
      OR (state IN ('ACCEPTED', 'REJECTED') AND decided_at IS NOT NULL AND decided_by IS NOT NULL)
      OR (state = 'SUPERSEDED' AND decided_at IS NOT NULL))
);
CREATE INDEX ai_proposal_requirement_id_idx ON ai_proposal (requirement_id) WHERE requirement_id IS NOT NULL;
CREATE INDEX ai_proposal_pending_idx ON ai_proposal (kind, proposed_at DESC) WHERE state = 'PENDING';
CREATE INDEX ai_proposal_accepted_elaboration_idx ON ai_proposal (requirement_id, decided_at DESC)
  WHERE kind = 'BRIEF_ELABORATION' AND state = 'ACCEPTED';
