-- VYB-0921 (D54): a counted rate limit. rate_limit_hit (V035) is a cooldown: one call per key per interval, which cannot express "600 a minute"
-- for a caller that legitimately sends bursts (the Agile Planner pushing its Functions). One row per key and fixed window; a request takes
-- the window's count up by one in a single atomic statement, so instances and threads agree, and is refused once the count passes the limit.
-- The window starts on a multiple of its length by the database's clock, so instances with skewed clocks agree.
CREATE TABLE rate_limit_window (
  key          TEXT NOT NULL,
  window_start TIMESTAMPTZ NOT NULL,
  hits         INT NOT NULL,
  PRIMARY KEY (key, window_start)
);
-- for the prune of windows no limit can still be counting
CREATE INDEX rate_limit_window_start_idx ON rate_limit_window (window_start);
