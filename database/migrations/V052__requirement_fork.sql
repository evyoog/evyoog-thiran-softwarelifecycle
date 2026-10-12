-- VYB-0942 (F18, D35): a new version of an approved requirement is a NEW requirement (its own id and key, status DRAFT) copied from
-- the approved one and linked to it and to the change request that allowed it. When the new version is approved, the original is
-- marked superseded by it. The original's status stays APPROVED (no new status; D17 stands).
ALTER TABLE requirement ADD COLUMN forked_from_id UUID REFERENCES requirement(id);
ALTER TABLE requirement ADD COLUMN forked_for_change_request_id UUID REFERENCES change_request(id);
ALTER TABLE requirement ADD COLUMN superseded_by_id UUID REFERENCES requirement(id);

-- A fork always names the change request it was made for, and the other way round.
ALTER TABLE requirement ADD CONSTRAINT requirement_fork_has_change_request
  CHECK ((forked_from_id IS NULL) = (forked_for_change_request_id IS NULL));
ALTER TABLE requirement ADD CONSTRAINT requirement_fork_not_self CHECK (forked_from_id IS NULL OR forked_from_id <> id);
ALTER TABLE requirement ADD CONSTRAINT requirement_superseded_not_self CHECK (superseded_by_id IS NULL OR superseded_by_id <> id);

-- An approved requirement has at most one new version in progress: a second fork is refused while one is live (not deleted, not
-- rejected). A rejected or deleted fork frees the original for another.
CREATE UNIQUE INDEX requirement_one_open_fork_idx ON requirement (forked_from_id)
  WHERE forked_from_id IS NOT NULL AND deleted_at IS NULL AND status <> 'REJECTED';
CREATE INDEX requirement_fork_cr_idx ON requirement (forked_for_change_request_id) WHERE forked_for_change_request_id IS NOT NULL;
CREATE INDEX requirement_superseded_by_idx ON requirement (superseded_by_id) WHERE superseded_by_id IS NOT NULL;
