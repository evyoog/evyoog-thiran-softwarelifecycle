-- VYB-0913 (F40): the connector framework's sync log.
--
-- One row per outbound operation a connector performed (or tried to), kept apart from the
-- registry row (integration_connection), which only holds the connection's current state. The log
-- is the history: which operation, under which idempotency key, how many attempts, how it ended.
-- It stores no payload and no secret, only the payload's size and SHA-256 so a delivery can be
-- matched to what was sent.
--
-- The partial unique index is what makes an idempotency key mean something across instances: at
-- most one row per (connection, key) may be IN_PROGRESS or SUCCEEDED, so a second instance racing
-- on the same operation cannot send it twice, and an operation that already succeeded is not sent
-- again. A FAILED row does not hold the key, so a failed operation can be retried.
CREATE TABLE connector_sync_log (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  connection_key  TEXT NOT NULL REFERENCES integration_connection(key),
  operation       TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  status          TEXT NOT NULL CHECK (status IN ('IN_PROGRESS', 'SUCCEEDED', 'FAILED')),
  attempts        INT  NOT NULL DEFAULT 0,
  http_status     INT,
  error           TEXT,
  payload_bytes   INT  NOT NULL,
  payload_sha256  TEXT NOT NULL,
  started_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  finished_at     TIMESTAMPTZ
);

CREATE UNIQUE INDEX connector_sync_log_live_key
  ON connector_sync_log (connection_key, idempotency_key)
  WHERE status IN ('IN_PROGRESS', 'SUCCEEDED');

-- newest first per connection (the health screen, VYB-0917) and the foreign key's index
CREATE INDEX connector_sync_log_connection_idx ON connector_sync_log (connection_key, started_at DESC);

-- the purge job deletes by age (PurgeService)
CREATE INDEX connector_sync_log_started_at_idx ON connector_sync_log (started_at);
