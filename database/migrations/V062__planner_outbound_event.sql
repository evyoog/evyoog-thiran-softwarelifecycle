-- VYB-0918a (D24, D52): telling the Agile Planner back. When something happens to a requirement the Planner sent (its status changes),
-- an event is written here in the same transaction as the change, and a scheduled job sends it, signed, to the Planner's webhook.
-- Written with the change so a rolled-back change sends nothing, and kept until sent so that an unreachable Planner loses nothing.
--
-- version: one number from one sequence, taken when the event is written. The Planner's contract says an older version never
-- overwrites a newer one, so every event carries it; two events about one requirement are written one after the other (the change
-- that makes the second waits for the first), so the later one always has the larger number. Gaps are normal.
--
-- A connection row for the Planner is added unconfigured: absence reads "not connected" until an administrator sets its URL and secret.
INSERT INTO integration_connection (key, connected, owns, direction) VALUES
 ('agile-planner', false, 'Functions it sends here, and what becomes of them (signed webhooks back)', 'BOTH')
ON CONFLICT (key) DO NOTHING;

CREATE SEQUENCE planner_outbound_event_version;

CREATE TABLE planner_outbound_event (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  event_id        UUID NOT NULL UNIQUE,
  version         BIGINT NOT NULL UNIQUE,
  event_type      TEXT NOT NULL CHECK (event_type IN ('requirement.status_changed', 'design_artifact.created', 'test_case.created', 'test_result.recorded')),
  requirement_id  UUID NOT NULL REFERENCES requirement(id),
  payload         JSONB NOT NULL,
  state           TEXT NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'SENT', 'FAILED')),
  attempts        INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_error      TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  sent_at         TIMESTAMPTZ
);
CREATE INDEX planner_outbound_event_due_idx ON planner_outbound_event (next_attempt_at) WHERE state = 'PENDING';
CREATE INDEX planner_outbound_event_requirement_idx ON planner_outbound_event (requirement_id, version);
CREATE INDEX planner_outbound_event_created_at_idx ON planner_outbound_event (created_at);
