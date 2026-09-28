-- =====================================================================
-- Phase 1 — idempotent creation (VYB-0132) and glossary conflict
-- detection (VYB-0102/0103), session 8.
-- =====================================================================

-- VYB-0132: a repeated request with the same key returns the first response instead
-- of creating a second row. Scoped by (key, endpoint) since the same key string could
-- theoretically be reused by a careless client against a different endpoint.
CREATE TABLE idempotency_key (
  key         TEXT NOT NULL,
  endpoint    TEXT NOT NULL,
  response_id UUID NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (key, endpoint)
);

-- VYB-0103: "flag a term defined differently in two applications" only means
-- something if a term's definition can actually vary by application. V001's
-- glossary_term is one global row per term — this adds the per-application layer
-- that was missing (the schema gap flagged in session 3's BUILD-REGISTER entry).
-- A NULL definition here means "uses the canonical glossary_term.definition
-- unchanged" — most usages will be this, not an override.
CREATE TABLE glossary_term_usage (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  term_id        UUID NOT NULL REFERENCES glossary_term(id) ON DELETE CASCADE,
  application_id UUID NOT NULL REFERENCES application(id) ON DELETE CASCADE,
  definition     TEXT,
  UNIQUE (term_id, application_id)
);
