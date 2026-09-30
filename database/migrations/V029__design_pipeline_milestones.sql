-- VYB-0816: two new node kinds so a generated flow can end in shared "Testing" and
-- "Deployment" milestone nodes, one per flow, fed by every requirement-derived node.
ALTER TABLE vyg_requirement.design_node DROP CONSTRAINT design_node_kind_check;
ALTER TABLE vyg_requirement.design_node ADD CONSTRAINT design_node_kind_check
    CHECK (kind = ANY (ARRAY['start', 'step', 'decision', 'integration', 'end', 'testing', 'deployment']));
