-- VYB-0837: exactly which capabilities a brief was generated for, empty meaning it was
-- generated app-wide rather than narrowed to specific capabilities. Recorded at
-- generation time so "was this app level or capability level" is a stored fact, not a
-- guess reconstructed later from which requirements happened to end up in the brief.
CREATE TABLE brief_capability (
  brief_id      UUID NOT NULL REFERENCES brief(id) ON DELETE CASCADE,
  capability_id UUID NOT NULL REFERENCES capability(id),
  PRIMARY KEY (brief_id, capability_id)
);
