-- VYB-0928: the release state machine (PLANNED, OPEN, FROZEN, RELEASED) and its readiness gates.
--
-- release.state already exists (V001) but nothing ever changed it. A transition is now a recorded event
-- (release_transition): who, when, from and to, the reason, and, when a failing gate was overridden, which
-- gates were failing. Readiness gates are configured platform-wide per guarded transition (release_gate)
-- and edited by an administrator; every edit is audited.

CREATE TABLE release_transition (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  release_id   UUID NOT NULL REFERENCES release(id) ON DELETE CASCADE,
  from_state   TEXT NOT NULL CHECK (from_state IN ('PLANNED','OPEN','FROZEN','RELEASED')),
  to_state     TEXT NOT NULL CHECK (to_state   IN ('PLANNED','OPEN','FROZEN','RELEASED')),
  reason       TEXT,
  overridden   BOOLEAN NOT NULL DEFAULT false,
  failed_gates JSONB,
  changed_by   UUID REFERENCES app_user(id),
  changed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- an override is only ever recorded with its reason and the gates it overrode
  CHECK (NOT overridden OR (btrim(coalesce(reason, '')) <> '' AND failed_gates IS NOT NULL))
);
CREATE INDEX release_transition_release_id_idx ON release_transition (release_id, changed_at DESC);

-- One row per guarded transition and gate. Only the two forward transitions that matter are guarded.
CREATE TABLE release_gate (
  transition TEXT NOT NULL CHECK (transition IN ('OPEN_TO_FROZEN','FROZEN_TO_RELEASED')),
  gate       TEXT NOT NULL CHECK (gate IN ('SCOPE_NOT_EMPTY','ALL_APPROVED','NO_CRITICAL_GAPS','NO_BLOCKED_ITEMS','VERIFIED_SHARE')),
  enabled    BOOLEAN NOT NULL,
  threshold  INT CHECK (threshold BETWEEN 0 AND 100),   -- only VERIFIED_SHARE has one: the minimum verified percentage
  PRIMARY KEY (transition, gate),
  CHECK ((gate = 'VERIFIED_SHARE') = (threshold IS NOT NULL))
);

-- Defaults: freezing needs a non-empty scope, every committed requirement Approved and no open critical gaps;
-- releasing adds that nothing in scope is blocked. The verified-share gate exists on both, off, at 100%.
INSERT INTO release_gate (transition, gate, enabled, threshold) VALUES
  ('OPEN_TO_FROZEN',    'SCOPE_NOT_EMPTY',  true,  NULL),
  ('OPEN_TO_FROZEN',    'ALL_APPROVED',     true,  NULL),
  ('OPEN_TO_FROZEN',    'NO_CRITICAL_GAPS', true,  NULL),
  ('OPEN_TO_FROZEN',    'NO_BLOCKED_ITEMS', false, NULL),
  ('OPEN_TO_FROZEN',    'VERIFIED_SHARE',   false, 100),
  ('FROZEN_TO_RELEASED','SCOPE_NOT_EMPTY',  true,  NULL),
  ('FROZEN_TO_RELEASED','ALL_APPROVED',     true,  NULL),
  ('FROZEN_TO_RELEASED','NO_CRITICAL_GAPS', true,  NULL),
  ('FROZEN_TO_RELEASED','NO_BLOCKED_ITEMS', true,  NULL),
  ('FROZEN_TO_RELEASED','VERIFIED_SHARE',   false, 100);
