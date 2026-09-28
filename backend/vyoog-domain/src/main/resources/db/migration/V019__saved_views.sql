-- A named set of requirement-grid filters, owned by the person who saved it.
--
-- Every column here is a parameter GET /requirements genuinely accepts. That is the whole
-- constraint on this table: a saved view must be replayable as a real server-side query,
-- because a view that filtered only the loaded page would report a count that disagrees
-- with what it claims to show.
CREATE TABLE saved_view (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id       UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  name           TEXT NOT NULL,
  status         TEXT CHECK (status IN ('DRAFT','IN_REVIEW','APPROVED','VERIFIED','REJECTED')),
  priority       TEXT CHECK (priority IN ('CRITICAL','HIGH','MEDIUM','LOW')),
  type           TEXT CHECK (type IN
                   ('FUNCTIONAL','NON_FUNCTIONAL','BUSINESS_RULE','INTERFACE','DATA','REPORT','SECURITY','COMPLIANCE')),
  title_contains TEXT,
  capability_id  UUID REFERENCES capability(id) ON DELETE CASCADE,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- One name per person: saving over an existing name replaces it rather than leaving
  -- two views nobody can tell apart in a list.
  UNIQUE (owner_id, name)
);

CREATE INDEX idx_saved_view_owner ON saved_view (owner_id, name);
