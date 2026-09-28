-- Closing out pending items flagged across every prior phase (see BUILD-REGISTER.md
-- session 13) rather than opening a Phase 6 — the "document" table itself has been in
-- the baseline since V001, unused until now.

-- =====================================================================
-- DOCUMENTS (VYB-0210–0215) — a document is a curated, ordered subset of a product's
-- requirements. "Item count" (VYB-0210 AC1) is just the row count here; "revision" is
-- the document's own bump each time its membership changes, distinct from any one
-- requirement's revision.
-- =====================================================================

CREATE TABLE document_requirement (
  document_id    UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
  requirement_id UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  ordinal        INT NOT NULL,
  PRIMARY KEY (document_id, requirement_id)
);
CREATE INDEX ON document_requirement (document_id, ordinal);

-- =====================================================================
-- TEAMS (VYB-0464) — "teams" was previously a proxy (distinct requirement owners);
-- a real team is now a real, small, first-class thing a user belongs to.
-- =====================================================================

CREATE TABLE team (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name       TEXT NOT NULL UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE team_member (
  team_id UUID NOT NULL REFERENCES team(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  PRIMARY KEY (team_id, user_id)
);

-- =====================================================================
-- DEFECT ROUTING (VYB-0322) — the baseline schema only ever had developer_id to route
-- to; DefectService already computes a tester too (whoever verified the requirement)
-- but had nowhere durable to put it, only a notification. This is that column.
-- =====================================================================

ALTER TABLE defect ADD COLUMN tester_id UUID REFERENCES app_user(id);

-- =====================================================================
-- CLARIFICATION ESCALATION (VYB-0334) — the age threshold and escalated_at already
-- exist (V005's clarification_escalation_days / clarification.escalated_at); the one
-- thing missing is recording *who* it went to, queryable, not just buried in the
-- audit event's JSONB.
-- =====================================================================

ALTER TABLE clarification ADD COLUMN escalated_to UUID REFERENCES app_user(id);

-- =====================================================================
-- NOTIFICATIONS (VYB-0356/0357) — a digest window, and enough on each row to coalesce
-- repeats of the same kind without a second events table.
-- =====================================================================

ALTER TABLE notification ADD COLUMN kind TEXT;
ALTER TABLE notification ADD COLUMN dedupe_key TEXT;
ALTER TABLE notification ADD COLUMN occurrence_count INT NOT NULL DEFAULT 1;
ALTER TABLE app_config ADD COLUMN notification_digest_window_minutes INT NOT NULL DEFAULT 15;

-- =====================================================================
-- TENANT BOOTSTRAP (VYB-0730) — records that the one-time provisioning step ran, so
-- it can't silently run twice and hand out a second "first" administrator.
-- =====================================================================

ALTER TABLE app_config ADD COLUMN bootstrapped_at TIMESTAMPTZ;

-- =====================================================================
-- JWT AUDIENCE (VYB-0007) — code-side only; there is still no audience mapper on the
-- real eVyoog realm client for this app to check against (see BUILD-REGISTER.md).
-- Nothing here — this note exists so the migration file itself explains why VYB-0007
-- doesn't have a schema change: it's a Spring Security config change, not a data one.
