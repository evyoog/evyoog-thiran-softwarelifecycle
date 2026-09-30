-- D13: per-phase due dates, and the client confirmation that gates them.
--
-- D10 added requirement.target_date — one overall date — and was careful to say it did
-- not reopen §13's exclusion of Gantt charts, capacity planning or effort estimation.
-- This adds three more dates and holds the same line: they are deadlines, recorded as
-- points, never durations. Nothing here computes or displays the gap between two phases,
-- because that gap is an effort estimate wearing a different hat.
--
-- Phase ASSIGNEES deliberately do not live in this table. requirement.developer_id and
-- requirement.tester_id already exist and TaskService derives four of its seven task
-- kinds from them; a second copy here would drift from the one that actually generates
-- somebody's work. Only implementer_id is new, because no column held it.

ALTER TABLE requirement ADD COLUMN implementer_id UUID REFERENCES app_user(id);

CREATE TABLE requirement_phase (
  requirement_id UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  phase          TEXT NOT NULL CHECK (phase IN ('DEVELOPMENT', 'TESTING', 'IMPLEMENTATION')),
  due_on         DATE NOT NULL,
  set_by         UUID REFERENCES app_user(id),
  set_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- Same vocabulary D10 established for moving requirement.target_date. A phase date
  -- that moves without a recorded reason is what made slippage unanswerable before.
  reason         TEXT,
  comment        TEXT,
  PRIMARY KEY (requirement_id, phase)
);

CREATE INDEX idx_requirement_phase_due ON requirement_phase (due_on);

-- The client confirmation. Vyoog has no customer entity and no client login: the closest
-- thing is app_user.status = 'EXTERNAL' (VYB-0702). So this records that one of our
-- people OBTAINED a confirmation — recorded_by is always the Vyoog user, and
-- confirmed_by_name is the person on the client side. The UI must render it that way and
-- never as though the client signed here.
CREATE TABLE requirement_date_commitment (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  requirement_id       UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  -- The date that was actually agreed. Kept rather than assumed to be the current
  -- target_date, which is the whole point: see requirement_commitment_state.
  target_date          DATE NOT NULL,
  confirmed_by_name    TEXT NOT NULL,
  confirmed_by_user_id UUID REFERENCES app_user(id),
  channel              TEXT NOT NULL CHECK (channel IN ('EMAIL', 'CALL', 'MEETING', 'DOCUMENT')),
  reference            TEXT,
  confirmed_on         DATE NOT NULL,
  recorded_by          UUID NOT NULL REFERENCES app_user(id),
  recorded_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_commitment_requirement ON requirement_date_commitment (requirement_id, recorded_at DESC);

-- Confirmed is a PREDICATE, not a flag — deliberately the same shape as
-- requirement_verification_state (V001), and for the same reason. A requirement is
-- Verified only when a test passed against its CURRENT revision; a date is Confirmed only
-- when a confirmation exists for the date CURRENTLY set. Move the date and the
-- confirmation stops applying by itself, with no job to run and no flag to remember to
-- clear: a client who agreed to 10 October has not agreed to 24 November.
CREATE VIEW requirement_commitment_state AS
SELECT r.id,
       r.target_date,
       CASE
         WHEN r.target_date IS NULL THEN 'NONE'
         WHEN EXISTS (SELECT 1 FROM requirement_date_commitment c
                       WHERE c.requirement_id = r.id AND c.target_date = r.target_date)
           THEN 'CONFIRMED'
         WHEN EXISTS (SELECT 1 FROM requirement_date_commitment c
                       WHERE c.requirement_id = r.id)
           THEN 'STALE'
         ELSE 'PROPOSED'
       END AS state
FROM requirement r;

COMMENT ON VIEW requirement_commitment_state IS
  'D13: whether the client has confirmed the date currently set. STALE means a '
  'confirmation exists but for a different date — the phase dates below it were split '
  'from a commitment that no longer stands.';

-- The derived fallback D10 established, extended to the new phases. Without these a
-- phase with no explicit date has nothing to fall back on and renders as NONE.
UPDATE app_config
   SET stage_stall_threshold_days =
       stage_stall_threshold_days || '{"DEVELOPMENT":10,"TESTING":5,"IMPLEMENTATION":3}'::jsonb
 WHERE id = 1;
