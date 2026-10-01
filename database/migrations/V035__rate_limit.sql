-- VYB-0908 (F09): the rate limiter's state, shared by every application instance.
--
-- It used to be a ConcurrentHashMap inside one JVM, so with two instances behind a load balancer
-- the cooldown on login, bulk edit and document analysis was per instance, not per user, and a
-- restart forgot every cooldown. One row per limited key; the time is the database's own clock so
-- instances with skewed clocks agree.
CREATE TABLE rate_limit_hit (
  key       TEXT PRIMARY KEY,
  last_call TIMESTAMPTZ NOT NULL
);

-- for the periodic prune of rows no cooldown can still care about
CREATE INDEX rate_limit_hit_last_call_idx ON rate_limit_hit (last_call);
