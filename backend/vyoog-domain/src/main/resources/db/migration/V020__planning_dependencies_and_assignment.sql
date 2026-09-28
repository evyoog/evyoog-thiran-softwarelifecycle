-- ============================================================================
-- D10 (Planning), part 2: requirement dependencies and assignment history.
--
-- Two genuinely new concepts. Everything else Planning needs already exists and is
-- reused rather than copied: the Capability → Application → Product chain carries scope,
-- app_config.stage_stall_threshold_days carries thresholds, requirement.target_date
-- carries the committed date, requirement.developer_id/owner_id carry current
-- responsibility, and audit_event carries history of value changes.
-- ============================================================================

-- A scheduling dependency: this requirement cannot sensibly be finished before that one.
--
-- Deliberately NOT a trace_link. TraceLinkType is semantic — SATISFIES, DERIVES,
-- VERIFIES, REFINES, CONFLICTS — and describes what a requirement *means* in relation to
-- another. "REQ-102 cannot start until REQ-101 lands" is a statement about order of work,
-- and overloading REFINES with it would make both unreadable.
CREATE TABLE requirement_dependency (
  requirement_id UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  depends_on_id  UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by     UUID REFERENCES app_user(id),
  PRIMARY KEY (requirement_id, depends_on_id),
  -- A requirement depending on itself is never meaningful and would make the cycle
  -- check's job ambiguous. Refused by the database, not only by the service.
  CONSTRAINT requirement_dependency_not_self CHECK (requirement_id <> depends_on_id)
);
CREATE INDEX idx_requirement_dependency_depends_on ON requirement_dependency (depends_on_id);

-- The business action: "user A assigned requirement X to user B, on this date".
--
-- Insert-only. This is history and it is never updated or deleted — the current
-- assignee stays on requirement.developer_id so "who owns this now" is one column read
-- rather than a scan of this table (§26). Reassignment appends a row; it does not edit one.
--
-- assigned_by is stored as a real user id rather than resolved from the team at read
-- time, because the team's lead changes and the historical record must keep saying who
-- actually made the assignment (§28).
CREATE TABLE planning_assignment (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  requirement_id UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  assigned_by    UUID NOT NULL REFERENCES app_user(id),
  assigned_to    UUID NOT NULL REFERENCES app_user(id),
  -- The team the assignee belonged to at the time, where one is known. Nullable: team
  -- membership is optional in this schema and an assignment must not require one.
  team_id        UUID REFERENCES team(id),
  assigned_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- Why the assignment or reassignment happened, using the same vocabulary as a planned
  -- date change so the two read consistently in a history view.
  reason         TEXT,
  comment        TEXT
);
CREATE INDEX idx_planning_assignment_requirement ON planning_assignment (requirement_id, assigned_at DESC);
CREATE INDEX idx_planning_assignment_assignee ON planning_assignment (assigned_to, assigned_at DESC);
