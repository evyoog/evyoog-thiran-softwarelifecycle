-- V013: VYB-0334's own disclosed gap — "there is no manager relationship modelled
-- anywhere in this schema (app_user has no manager_id)" — a real, addressable schema
-- gap, not an infra blocker. Mirrors delegate_id's own self-referencing FK exactly.
ALTER TABLE app_user ADD COLUMN manager_id UUID REFERENCES app_user(id);
