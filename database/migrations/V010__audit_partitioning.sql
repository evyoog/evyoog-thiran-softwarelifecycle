-- =====================================================================
-- VYB-0723 (session 16): audit retention/archival, for real.
--
-- audit_event becomes a genuinely range-partitioned table (by occurred_at, one
-- partition per calendar month) so retention can DETACH a whole month's partition
-- once it's past app_config.audit_retention_days, instead of DELETE-ing individual
-- rows. This matters because DETACH PARTITION is DDL, not row-level DML — it never
-- fires the append-only trigger (BEFORE UPDATE OR DELETE, FOR EACH ROW), so "old
-- audit data becomes eligible for archival" and "audit_event is append-only, no
-- exceptions" stop being in tension. A detached partition is renamed, not dropped —
-- archived means set aside and still queryable by name, not destroyed.
-- =====================================================================

ALTER TABLE audit_event RENAME TO audit_event_pre_partition;
DROP TRIGGER audit_no_update ON audit_event_pre_partition;

CREATE TABLE audit_event (
  id          UUID NOT NULL DEFAULT gen_random_uuid(),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  actor_id    UUID,
  actor_type  TEXT NOT NULL DEFAULT 'USER' CHECK (actor_type IN ('USER','SERVICE','SYSTEM')),
  action      TEXT NOT NULL,
  object_type TEXT,
  object_id   UUID,
  before      JSONB,
  after       JSONB,
  request_id  TEXT,
  ip          INET,
  -- A partitioned table's unique/PK constraints must include the partition key —
  -- (id, occurred_at) rather than the original bare id. Nothing in this codebase
  -- holds a foreign key into audit_event (it's a leaf log table), so widening the
  -- key doesn't ripple anywhere.
  PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE INDEX ON audit_event (occurred_at DESC);

-- Everything outside the explicit monthly ranges below lands here rather than
-- failing the insert — a safety net against "no partition exists yet" ever
-- silently dropping a write, not a place anything is meant to stay long-term.
CREATE TABLE audit_event_default PARTITION OF audit_event DEFAULT;

-- One partition per month, one month back through six months ahead of whenever
-- this migration actually runs. AuditRetentionService.ensureFuturePartitions()
-- keeps extending this window forward on a schedule; this just seeds enough room
-- that the app never has zero forward partitions on a fresh boot.
DO $$
DECLARE
  start_month date := date_trunc('month', now() - interval '1 month');
  i int;
  part_start date;
  part_end date;
  part_name text;
BEGIN
  FOR i IN 0..7 LOOP
    part_start := start_month + (i || ' months')::interval;
    part_end := part_start + interval '1 month';
    part_name := 'audit_event_' || to_char(part_start, 'YYYY_MM');
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS %I PARTITION OF audit_event FOR VALUES FROM (%L) TO (%L)',
      part_name, part_start, part_end);
  END LOOP;
END $$;

-- Move every existing row across — this environment's audit history is only ever a
-- few days old, so this is small today, but the migration is correct regardless of
-- how much existed: rows outside the seeded monthly ranges above land in the
-- DEFAULT partition, not lost.
INSERT INTO audit_event (id, occurred_at, actor_id, actor_type, action, object_type, object_id, before, after, request_id, ip)
SELECT id, occurred_at, actor_id, actor_type, action, object_type, object_id, before, after, request_id, ip
FROM audit_event_pre_partition;

DROP TABLE audit_event_pre_partition;

-- Row-level BEFORE triggers defined on a partitioned table's parent propagate
-- automatically to every partition it has now AND every partition created later
-- (PostgreSQL 11+) — one trigger definition, not one per partition, ever.
CREATE TRIGGER audit_no_update BEFORE UPDATE OR DELETE ON audit_event
  FOR EACH ROW EXECUTE FUNCTION audit_is_append_only();
