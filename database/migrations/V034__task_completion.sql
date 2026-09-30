-- VYB-0838 (D19 in docs/DECISIONS.md): My Work's checkbox needs a real action behind
-- it, not a client-side toggle that reappears on reload. This is deliberately not a
-- task table (Principle 4 stays true otherwise: nothing here is ever authored, and a
-- task whose underlying condition still holds keeps being derived and shown, checkbox
-- or not) — task_completion only records that a person dismissed one specific derived
-- task once, at the object revision it was dismissed at.
--
-- object_revision is the same "predicate, not a flag" shape as
-- requirement_verification_state.has_stale_evidence: a completion only matches while
-- the object hasn't moved past the revision it was recorded against. If the
-- requirement's revision has since advanced, the completion stops matching and the
-- task reopens on its own — nobody has to un-check anything.
--
-- object_revision is null for a review-scoped kind (REVIEWER_PENDING/APPROVER_AWAITING,
-- object_id = review.id) — a review carries no revision of its own, so that dismissal
-- is valid until the review itself closes (at which point the task stops being derived
-- anyway) or is explicitly reopened.
CREATE TABLE task_completion (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  kind            TEXT NOT NULL,
  object_id       UUID NOT NULL,
  object_revision INT,
  user_id         UUID NOT NULL REFERENCES app_user(id),
  completed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE NULLS NOT DISTINCT (kind, object_id, object_revision, user_id)
);

CREATE INDEX task_completion_user_idx ON task_completion(user_id, completed_at);
