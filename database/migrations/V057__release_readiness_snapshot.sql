-- VYB-0956 (F24, D43): the history of each release's readiness. Readiness itself is derived on every read (VYB-0476) and nothing
-- kept it, so a trend needs a record: one row per release per day, taken by a nightly job. A day with no row is a day with no data,
-- never a zero. The figures are those the release screen and the gates use (committed scope, approved, verified, blocked, open
-- critical gaps); a row is a measurement and is never edited after the day it describes, only replaced if the job runs twice that day.

CREATE TABLE release_readiness_snapshot (
  release_id    UUID NOT NULL REFERENCES release(id) ON DELETE CASCADE,
  taken_on      DATE NOT NULL,
  committed     INT  NOT NULL CHECK (committed >= 0),
  approved      INT  NOT NULL CHECK (approved >= 0),
  verified      INT  NOT NULL CHECK (verified >= 0),
  blocked       INT  NOT NULL CHECK (blocked >= 0),
  critical_gaps INT  NOT NULL CHECK (critical_gaps >= 0),
  taken_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (release_id, taken_on),
  CHECK (approved <= committed AND verified <= committed AND blocked <= committed)
);
