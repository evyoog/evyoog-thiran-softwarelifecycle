-- VYB-0915 (D24, D50): the back-reference from a requirement the Agile Planner created to the Function it came from. The Planner sends a
-- Function once to be created here as a draft requirement; from then on the requirement is ours (its text, status, designs, tests),
-- and the Planner holds only a reference back. This table is that reference and nothing else: no copy of the Function's fields except
-- the ids it sent, and a hash of what it sent last, so that a repeat of the same push is recognised and a changed one is not applied
-- silently (a later change will arrive as a proposal; until that exists it is refused with a clear answer).
--
-- One Function is one requirement (the first answer to the Planner's open question 1), so both ids are unique. The source ids other than
-- the Function's are kept as text because they belong to the Planner and Macro Planner, and nothing here can check them.
CREATE TABLE planner_function_link (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  source_system        TEXT NOT NULL DEFAULT 'agile_planner' CHECK (source_system = 'agile_planner'),
  source_function_id   TEXT NOT NULL,
  source_feature_id    TEXT,
  source_capability_id TEXT,
  source_goal_id       TEXT,
  requirement_id       UUID NOT NULL REFERENCES requirement(id),
  content_hash         TEXT NOT NULL,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (source_system, source_function_id),
  UNIQUE (requirement_id)
);
