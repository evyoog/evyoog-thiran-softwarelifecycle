-- VYB-0949 (F41, D40): the risk register. A risk has a likelihood and an impact (1 to 5 each, qualitative: no money, no hours), an owner,
-- a mitigation and a status, and links to the requirements it touches and the releases it threatens. The rating and band are derived
-- from likelihood and impact on every read, never stored. A risk is never deleted: it is closed, and stays.
CREATE SEQUENCE risk_key_seq START 1;

CREATE TABLE risk (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  key           TEXT NOT NULL UNIQUE,
  title         TEXT NOT NULL,
  description   TEXT,
  likelihood    SMALLINT NOT NULL CONSTRAINT risk_likelihood_check CHECK (likelihood BETWEEN 1 AND 5),
  impact        SMALLINT NOT NULL CONSTRAINT risk_impact_check CHECK (impact BETWEEN 1 AND 5),
  status        TEXT NOT NULL DEFAULT 'OPEN' CONSTRAINT risk_status_check CHECK (status IN ('OPEN', 'MITIGATING', 'ACCEPTED', 'CLOSED')),
  status_reason TEXT,
  owner_id      UUID REFERENCES app_user(id),
  mitigation    TEXT,
  created_by    UUID REFERENCES app_user(id),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  closed_at     TIMESTAMPTZ,
  CONSTRAINT risk_closed_at_matches_status CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);
CREATE INDEX risk_status_idx ON risk (status);
CREATE INDEX risk_owner_idx ON risk (owner_id) WHERE owner_id IS NOT NULL;
CREATE INDEX risk_created_by_idx ON risk (created_by) WHERE created_by IS NOT NULL;

CREATE TABLE risk_requirement (
  risk_id        UUID NOT NULL REFERENCES risk(id) ON DELETE CASCADE,
  requirement_id UUID NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
  PRIMARY KEY (risk_id, requirement_id)
);
CREATE INDEX risk_requirement_requirement_idx ON risk_requirement (requirement_id);

CREATE TABLE risk_release (
  risk_id    UUID NOT NULL REFERENCES risk(id) ON DELETE CASCADE,
  release_id UUID NOT NULL REFERENCES release(id) ON DELETE CASCADE,
  PRIMARY KEY (risk_id, release_id)
);
CREATE INDEX risk_release_release_idx ON risk_release (release_id);
