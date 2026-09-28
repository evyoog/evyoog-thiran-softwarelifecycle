-- Team Lead, which the schema has never had: team was (id, name) and team_member was
-- (team_id, user_id) with no role at all, so "notify the lead" and "may this person
-- assign work" had nothing to resolve against.
--
-- A role on the membership rather than a lead_user_id on the team, deliberately: a team
-- can have two leads, a lead is always also a member, and demoting somebody is an update
-- to one row rather than a nulled foreign key on another table.
ALTER TABLE team_member ADD COLUMN role TEXT NOT NULL DEFAULT 'MEMBER'
  CHECK (role IN ('LEAD', 'MEMBER'));

CREATE INDEX idx_team_member_leads ON team_member (team_id) WHERE role = 'LEAD';

-- Who the assigner was *at the time*, kept as text on the assignment itself.
--
-- §28: the historical record must keep saying what actually happened. Resolving the role
-- at read time would rewrite history every time somebody is promoted — an assignment
-- made by a lead who has since stepped down would start reporting as made by a member.
ALTER TABLE planning_assignment ADD COLUMN assigned_by_role TEXT;
