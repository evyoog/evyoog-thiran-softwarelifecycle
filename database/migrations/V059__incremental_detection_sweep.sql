-- VYB-0960 (F43, D48): the nightly detection sweep no longer compares every requirement's embedding with every other (hours at 50,000
-- requirements). It checks only requirements whose embedding is new or changed since the last pass, each exactly as a write would, and
-- carries on from where it stopped if it runs out of time. That needs three things recorded.
--
-- finding.related_object_id: a finding about a PAIR of requirements (a possible duplicate, a contradiction) is about the lower id (object_id)
-- and names the other one in its fingerprint. Re-checking the OTHER requirement could not see it, so a pair that stopped being similar
-- when the second requirement changed was never resolved except by a full sweep. The other requirement is now a column, so a re-check of
-- either side finds the finding. NULL for every kind of finding that is about one object.
ALTER TABLE finding ADD COLUMN related_object_id UUID;
CREATE INDEX finding_related_object_id_idx ON finding (related_object_id) WHERE related_object_id IS NOT NULL;
-- Existing pair findings: their fingerprint ends in the other requirement's id (dup|REQUIREMENT|A|A|B and conflict|REQUIREMENT|A|B).
UPDATE finding
   SET related_object_id = substring(fingerprint from '([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$')::uuid
 WHERE rule_key IN ('dup', 'conflict')
   AND fingerprint ~ '\|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

-- detection_sweep_state: how far the sweep of one rule has got through the changed requirements, as the position (embedded_at, id) of the
-- last requirement checked, and a fingerprint of the settings the checks were made under (the rule's threshold): when the settings change
-- the position is reset, so the whole set is checked again under them. processed_last and finished_at say what the last pass did.
CREATE TABLE detection_sweep_state (
  rule_key       TEXT PRIMARY KEY REFERENCES gap_rule_template(key),
  cursor_at      TIMESTAMPTZ,
  cursor_id      UUID,
  settings_hash  TEXT,
  processed_last INT NOT NULL DEFAULT 0,
  finished_at    TIMESTAMPTZ,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The sweep reads requirements in (embedded_at, requirement_id) order, a page at a time.
CREATE INDEX requirement_embedding_embedded_at_idx ON requirement_embedding (embedded_at, requirement_id);
