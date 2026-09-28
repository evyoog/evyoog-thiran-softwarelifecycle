-- V011: the Portfolio dashboard redesign needs real fields the schema never had, plus
-- two renames of columns that already existed but were never surfaced in any UI —
-- `tagline` and `description` on `product`, both real, both unused, the exact pattern
-- prior sessions kept finding (see BUILD-REGISTER.md session 16's `config`/`ip`
-- write-ups). Renamed rather than left aliased so the column name matches what it
-- actually holds: `vertical` (the italic line under a product's name, e.g. "Resource
-- Intelligence") and `purpose` (one sentence — what the product optimises/manages).

ALTER TABLE product RENAME COLUMN tagline TO vertical;
ALTER TABLE product RENAME COLUMN description TO purpose;

-- Real owner, not a name typed in a text box — same app_user FK every other
-- assignee field in this schema already uses (capability.owner_id, requirement's
-- clarification assignee, etc.).
ALTER TABLE product ADD COLUMN owner_id UUID REFERENCES app_user(id);

ALTER TABLE product ADD COLUMN lifecycle_status TEXT NOT NULL DEFAULT 'IN_DEVELOPMENT'
  CHECK (lifecycle_status IN ('IN_DEVELOPMENT', 'LIVE', 'MAINTENANCE', 'DEPRECATED', 'RETIRED'));

-- A fixed, named icon key — not a free-text field a future column could drift
-- against — resolved to a real lucide-react icon + accent colour pair in the
-- frontend's MARKS table, never invented per-row.
ALTER TABLE product ADD COLUMN mark TEXT NOT NULL DEFAULT 'box'
  CHECK (mark IN ('box', 'sun', 'shuffle', 'calendar', 'database', 'target', 'layers',
                   'bar-chart', 'git-branch', 'shield', 'bookmark', 'file-text'));
