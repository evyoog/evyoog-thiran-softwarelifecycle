-- VYB-0944 (F17, D26): review rounds are retired. The endpoints, service and My Work tasks are gone from the code; review,
-- review_participant, review_item and their comments stay as read-only history and are not touched here. This migration deals
-- with the one piece of data that keeps living on its own: the 'sod' gap rule watched signatures on those rounds, and nothing
-- can detect it any more.
--
-- The rule's template row stays (finding.rule_key references it, and the findings it raised are history); it is marked retired so
-- the rules screen stops offering it, and the findings still open are resolved. Dismissed and accepted ones are left as decided.
ALTER TABLE gap_rule_template ADD COLUMN retired_at TIMESTAMPTZ;

UPDATE gap_rule_template SET retired_at = now() WHERE key = 'sod';

UPDATE finding
   SET state = 'RESOLVED', resolved_at = now()
 WHERE rule_key = 'sod' AND state = 'OPEN';

INSERT INTO audit_event (actor_type, action, object_type, after)
VALUES ('SYSTEM', 'rule.retired', 'GAP_RULE',
        jsonb_build_object('key', 'sod', 'reason', 'review rounds retired (D26)',
                           'resolvedFindings', (SELECT count(*) FROM finding WHERE rule_key = 'sod' AND state = 'RESOLVED')));
