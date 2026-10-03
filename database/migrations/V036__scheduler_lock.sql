-- VYB-0909 (F33-F35): one row per scheduled job, so that with more than one application instance
-- only one of them runs a given job at a time.
--
-- Before this, the nightly detection sweep, the audit-partition maintenance, the clarification
-- escalation and the 2-second outbox relay each ran on EVERY instance. Two instances meant the
-- escalation notified twice and two relays raced over the same unpublished outbox rows.
--
-- locked_until is the lease: an instance that dies mid-job stops holding it when it lapses.
-- locked_by is a per-acquisition token, so a holder whose lease lapsed cannot release a newer holder's.
CREATE TABLE scheduler_lock (
  name         TEXT PRIMARY KEY,
  locked_by    TEXT        NOT NULL,
  locked_at    TIMESTAMPTZ NOT NULL,
  locked_until TIMESTAMPTZ NOT NULL
);
