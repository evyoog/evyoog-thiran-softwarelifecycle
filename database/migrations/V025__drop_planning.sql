-- Removes the Planning feature: the screen, its endpoints and the tables behind it.
--
-- Flyway is forward-only on this project (CLAUDE.md), so this is not reversible. The
-- dates, dependency edges, assignment history and phase schedules these tables held are
-- gone once this runs — that was the explicit instruction, recorded here because a future
-- reader finding an empty planning package will want to know the data went with it.
--
-- team_member.role (V021) deliberately stays: it was added alongside planning permissions
-- but describes team structure, which outlives this feature.

-- V023's requirement_commitment_state reads requirement.target_date and
-- requirement_date_commitment directly, so it has to go before either of them —
-- Postgres refuses a bare DROP TABLE/DROP COLUMN while a view still depends on it.
DROP VIEW IF EXISTS requirement_commitment_state;

DROP INDEX IF EXISTS idx_planning_assignment_assignee;
DROP INDEX IF EXISTS idx_planning_assignment_requirement;
DROP INDEX IF EXISTS idx_requirement_dependency_depends_on;
DROP INDEX IF EXISTS idx_commitment_requirement;
DROP INDEX IF EXISTS idx_requirement_phase_due;
DROP INDEX IF EXISTS idx_requirement_target_date;

DROP TABLE IF EXISTS planning_assignment;
DROP TABLE IF EXISTS requirement_dependency;
DROP TABLE IF EXISTS requirement_date_commitment;
DROP TABLE IF EXISTS requirement_phase;

ALTER TABLE requirement DROP COLUMN IF EXISTS target_date;
-- V023 added this for the phase schedule; nothing else ever read it.
ALTER TABLE requirement DROP COLUMN IF EXISTS implementer_id;
