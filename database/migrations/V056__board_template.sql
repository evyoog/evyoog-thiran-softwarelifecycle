-- VYB-0953 (F42, D42): a board template is a named board layout: which statuses are columns, in what order, and a scope (a product,
-- an application or a capability, at most one) and an optional type and priority filter. A template belongs to a team; one default
-- template (no team) exists for everyone and cannot be deleted. The board itself is driven by requirement status and stores nothing.
-- The columns are statuses by name; the service checks them (a CHECK list of statuses here is what went stale in saved_view, V038).
CREATE TABLE board_template (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  team_id        UUID REFERENCES team(id) ON DELETE CASCADE,
  name           TEXT NOT NULL,
  columns        TEXT[] NOT NULL,
  product_id     UUID REFERENCES product(id) ON DELETE CASCADE,
  application_id UUID REFERENCES application(id) ON DELETE CASCADE,
  capability_id  UUID REFERENCES capability(id) ON DELETE CASCADE,
  type           TEXT,
  priority       TEXT,
  created_by     UUID REFERENCES app_user(id),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT board_template_has_columns CHECK (cardinality(columns) >= 1),
  CONSTRAINT board_template_one_scope CHECK (
    (product_id IS NOT NULL)::int + (application_id IS NOT NULL)::int + (capability_id IS NOT NULL)::int <= 1)
);

-- One name per team (case-insensitive); the team-less default is its own group.
CREATE UNIQUE INDEX board_template_name_per_team
  ON board_template (COALESCE(team_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name));
CREATE INDEX board_template_team_idx ON board_template (team_id) WHERE team_id IS NOT NULL;
CREATE INDEX board_template_product_idx ON board_template (product_id) WHERE product_id IS NOT NULL;
CREATE INDEX board_template_application_idx ON board_template (application_id) WHERE application_id IS NOT NULL;
CREATE INDEX board_template_capability_idx ON board_template (capability_id) WHERE capability_id IS NOT NULL;
CREATE INDEX board_template_created_by_idx ON board_template (created_by) WHERE created_by IS NOT NULL;

INSERT INTO board_template (name, columns)
VALUES ('Default', ARRAY['DRAFT', 'IN_REVIEW', 'REVIEWED', 'NEEDS_REVISION', 'APPROVED', 'REJECTED']);
