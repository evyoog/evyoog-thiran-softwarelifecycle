-- Widens brief.target to accept CURSOR. Forward-only: the constraint is dropped and
-- recreated with the fourth value, which is the only way to change a CHECK in place.
--
-- Every existing row already satisfies the new constraint (it is a strict superset of the
-- old one), so this needs no backfill and cannot fail on populated data.
ALTER TABLE vyg_requirement.brief DROP CONSTRAINT brief_target_check;
ALTER TABLE vyg_requirement.brief ADD CONSTRAINT brief_target_check
  CHECK (target IN ('CLAUDE_CODE', 'CODEX', 'CURSOR', 'HUMAN'));
