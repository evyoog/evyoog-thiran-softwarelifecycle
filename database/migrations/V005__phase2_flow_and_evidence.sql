-- =====================================================================
-- Phase 2 — flow and evidence, session 9.
--
-- Almost everything Phase 2 needs already exists in V001's baseline (review,
-- review_item, review_participant, test_case, test_run, verification, defect,
-- clarification, change_request, outbox_event, notification, access_grant,
-- service_account) — that schema was written against the full spec up front.
-- This migration only adds the handful of things the baseline didn't cover.
-- =====================================================================

-- VYB-0306 AC1: comments against a review round itself, distinct from
-- requirement_comment (which is against one requirement). requirement_id is
-- nullable so a round-level comment and a per-requirement-within-the-round
-- comment share one table without forcing every row to name a requirement.
CREATE TABLE review_comment (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  review_id      UUID NOT NULL REFERENCES review(id) ON DELETE CASCADE,
  requirement_id UUID REFERENCES requirement(id),
  author_id      UUID NOT NULL REFERENCES app_user(id),
  body           TEXT NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON review_comment (review_id);

-- VYB-0302 AC2: a signature is append-only. review_participant is a mutable row
-- (role, signed_at, signature_acr) rather than an append-only ledger, so the
-- guarantee is enforced here the same way audit_event's is in V001 — once
-- signed_at is set, no update may change it or the achieved level again.
CREATE OR REPLACE FUNCTION review_signature_is_append_only() RETURNS TRIGGER AS $fn$
BEGIN
  IF OLD.signed_at IS NOT NULL AND (
       NEW.signed_at IS DISTINCT FROM OLD.signed_at
       OR NEW.signature_acr IS DISTINCT FROM OLD.signature_acr) THEN
    RAISE EXCEPTION 'a signature, once recorded, cannot be changed';
  END IF;
  RETURN NEW;
END;
$fn$ LANGUAGE plpgsql;

CREATE TRIGGER review_signature_append_only
  BEFORE UPDATE ON review_participant
  FOR EACH ROW EXECUTE FUNCTION review_signature_is_append_only();

-- VYB-0320/0390: defects and change requests are raised by people through Vyoog
-- itself (unlike test cases, whose keys arrive from the CI system that owns
-- them) — so, like requirement keys, they're allocated from a sequence.
CREATE SEQUENCE defect_key_seq START WITH 1;
CREATE SEQUENCE change_request_key_seq START WITH 1;

-- VYB-0315/0316: a commit has no natural UUID (its identity is a SHA), and the
-- trace graph's CODE object type needs one — this is that mapping, plus the one
-- fact the untraced-commit detector needs (whether a trailer was found) that
-- can't be derived by "is there a trace_link" once we already know there isn't.
CREATE TABLE ingested_commit (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  sha          TEXT NOT NULL UNIQUE,
  message      TEXT,
  author_email TEXT,
  has_trailer  BOOLEAN NOT NULL DEFAULT false,
  ingested_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- VYB-0304 AC3: separation-of-duties violations join the same finding mechanism
-- Phase 1 built for the other twelve rules, rather than a bespoke table.
INSERT INTO gap_rule_template (key, name, technique, severity, description, phase) VALUES
 ('sod', 'Separation of duties violation', 'RULE', 'crit',
  'A requirement''s owner or author signed its own approval', 2);

-- VYB-0334/0349: "the threshold is configurable per tenant" — this deployment's
-- one app_config row is where that lives, next to the requirement key prefix.
ALTER TABLE app_config
  ADD COLUMN clarification_escalation_days SMALLINT NOT NULL DEFAULT 3,
  ADD COLUMN stage_stall_threshold_days JSONB NOT NULL DEFAULT
    '{"DRAFT":5,"IN_REVIEW":3,"APPROVED":5}';

-- VYB-0334 AC1: escalating twice for the same ageing clarification would just be
-- noise — this is when it last happened (null = never), so the nightly job can skip
-- what it already escalated instead of re-notifying every run.
ALTER TABLE clarification ADD COLUMN escalated_at TIMESTAMPTZ;

-- VYB-0390: "against one or more approved requirements with a rationale" — the
-- baseline's change_request has neither the rationale column nor a place to record
-- which requirements it covers (the impact counts are aggregates, not the scope
-- itself). Both are needed to persist what the requirement actually asked for.
ALTER TABLE change_request ADD COLUMN rationale TEXT NOT NULL DEFAULT '';

CREATE TABLE change_request_requirement (
  change_request_id UUID NOT NULL REFERENCES change_request(id) ON DELETE CASCADE,
  requirement_id     UUID NOT NULL REFERENCES requirement(id),
  PRIMARY KEY (change_request_id, requirement_id)
);
