-- VYB-0918b (D24, D52): the other three events the Agile Planner is told about (a design artifact for its requirement, a test case for it,
-- a test result against it) can each be reached by more than one path, and a path can run again (a design regenerated, a CI run ingested
-- twice). dedupe_key names what an event is about, so the same thing is queued once: the first writer wins and a repeat writes nothing.
-- A status change has no key (every change is its own event).
ALTER TABLE planner_outbound_event ADD COLUMN dedupe_key TEXT;
CREATE UNIQUE INDEX planner_outbound_event_dedupe_idx ON planner_outbound_event (dedupe_key);
