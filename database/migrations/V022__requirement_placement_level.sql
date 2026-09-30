-- D12: a requirement may sit at product, application or capability level.
--
-- Spec §1.2 said "every requirement belongs to exactly one capability … rigid on
-- purpose", and that rigidity is what made cross-app duplicate detection and scope
-- arithmetic possible. This relaxes the *placement*, not the arithmetic: the whole point
-- of requirement_scope below is that scope questions keep exactly one answer.
--
-- Deliberately NOT denormalised. A capability-level requirement does not get its product
-- and application copied onto the row: moving a capability between applications would
-- then leave every requirement under it pointing at the old one, and nothing would say
-- so. The view resolves upward at read time instead, so there is nothing to drift.

ALTER TABLE requirement
  ADD COLUMN product_id     UUID REFERENCES product(id),
  ADD COLUMN application_id UUID REFERENCES application(id);

-- At most one, never several. "At most" rather than "exactly" because an unplaced
-- requirement is already legal and the import queue depends on it: candidates arrive
-- before anybody has said where they belong.
ALTER TABLE requirement ADD CONSTRAINT requirement_one_placement CHECK (
  (capability_id IS NOT NULL)::int
  + (application_id IS NOT NULL)::int
  + (product_id IS NOT NULL)::int <= 1
);

CREATE INDEX idx_requirement_product ON requirement (product_id) WHERE product_id IS NOT NULL;
CREATE INDEX idx_requirement_application ON requirement (application_id) WHERE application_id IS NOT NULL;

-- The single definition of "where does this requirement sit". Every scope query joins
-- this rather than testing capability_id itself; a query that tests capability_id
-- directly silently drops product- and application-level rows, which is precisely how a
-- requirement would go missing from a Delivery brief without anybody being told.
CREATE VIEW requirement_scope AS
SELECT r.id AS requirement_id,
       COALESCE(r.product_id, direct_app.product_id, cap_app.product_id) AS product_id,
       COALESCE(r.application_id, c.application_id)                      AS application_id,
       r.capability_id,
       CASE
         WHEN r.capability_id  IS NOT NULL THEN 'CAPABILITY'
         WHEN r.application_id IS NOT NULL THEN 'APPLICATION'
         WHEN r.product_id     IS NOT NULL THEN 'PRODUCT'
         ELSE 'UNPLACED'
       END AS level
FROM requirement r
LEFT JOIN capability  c          ON c.id = r.capability_id
LEFT JOIN application cap_app    ON cap_app.id = c.application_id
LEFT JOIN application direct_app ON direct_app.id = r.application_id;

COMMENT ON VIEW requirement_scope IS
  'D12: resolves each requirement to its (product, application, capability) and its '
  'placement level. Scope queries join this; they must not test requirement.capability_id '
  'directly, which excludes product- and application-level requirements.';

-- D12: an import candidate carries the same placement choice, so a document of
-- product-wide rules can be committed at product level in one action rather than
-- imported and then moved one row at a time.
ALTER TABLE import_candidate
  ADD COLUMN product_id     UUID REFERENCES product(id),
  ADD COLUMN application_id UUID REFERENCES application(id);

ALTER TABLE import_candidate ADD CONSTRAINT import_candidate_one_placement CHECK (
  (capability_id IS NOT NULL)::int
  + (application_id IS NOT NULL)::int
  + (product_id IS NOT NULL)::int <= 1
);
