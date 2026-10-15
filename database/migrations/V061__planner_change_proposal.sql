-- VYB-0919 (D24, D51): a later change to a Function the Agile Planner has already sent arrives as a proposal on its requirement, which a
-- person accepts or rejects. Nothing the Planner sends overwrites a requirement (the requirement is ours once created, D50), so the
-- changed words wait here, beside the requirement, until someone decides.
--
-- At most one proposal per Function is PENDING: a newer change supersedes the older one that nobody has decided yet, and a Planner
-- that sends the requirement's own current words again supersedes it too. A decided proposal is kept as the record of what was
-- offered and by whom it was decided. The proposal carries its own copy of the words the Planner sent (title, statement, priority,
-- criteria) because they are the Planner's offer, not yet anything of this requirement.
CREATE TABLE planner_change_proposal (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  link_id         UUID NOT NULL REFERENCES planner_function_link(id),
  requirement_id  UUID NOT NULL REFERENCES requirement(id),
  state           TEXT NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'ACCEPTED', 'REJECTED', 'SUPERSEDED')),
  title           TEXT NOT NULL,
  statement       TEXT NOT NULL,
  priority        TEXT NOT NULL CHECK (priority IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
  criteria        TEXT[] NOT NULL DEFAULT '{}',
  content_hash    TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  decided_by      UUID REFERENCES app_user(id),
  decided_at      TIMESTAMPTZ,
  decision_note   TEXT,
  applied_revision INT,
  CHECK ((state IN ('ACCEPTED', 'REJECTED')) = (decided_by IS NOT NULL AND decided_at IS NOT NULL))
);
CREATE INDEX planner_change_proposal_link_id_idx ON planner_change_proposal (link_id);
CREATE INDEX planner_change_proposal_requirement_id_idx ON planner_change_proposal (requirement_id, received_at DESC);
CREATE INDEX planner_change_proposal_decided_by_idx ON planner_change_proposal (decided_by);
CREATE UNIQUE INDEX planner_change_proposal_one_pending_idx ON planner_change_proposal (link_id) WHERE state = 'PENDING';
CREATE UNIQUE INDEX planner_change_proposal_key_idx ON planner_change_proposal (idempotency_key);
