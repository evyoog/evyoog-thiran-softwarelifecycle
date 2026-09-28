-- Phase 5 — Administration and hardening.
--
-- access_grant, service_account, audit_event and integration_connection already
-- exist in V001__baseline.sql (written up front against the full spec, like every
-- other phase's tables) — this migration only adds what those didn't yet carry:
-- key-rotation tracking, integration failure/direction tracking, webhook replay
-- protection, and the settings this phase's administrator screens read and write.

-- =====================================================================
-- USERS — departure timestamp (VYB-0705)
-- =====================================================================

-- VYB-0705 AC1: "the report names when the departure was recorded" needs an actual
-- timestamp, not just the current status string — this is that timestamp, updated
-- whenever status changes (not only on departure), so it also answers "since when
-- has this user been on LEAVE" for free.
ALTER TABLE app_user ADD COLUMN status_changed_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- =====================================================================
-- SERVICE ACCOUNTS — key rotation (VYB-0712)
-- =====================================================================

ALTER TABLE service_account
  ADD COLUMN previous_client_id TEXT,
  ADD COLUMN previous_key_issued_at TIMESTAMPTZ,
  -- the previous key/clientId stays valid until this instant (AC1); null means no
  -- rotation has ever happened, or the overlap window has already been consumed.
  ADD COLUMN key_rotation_overlap_until TIMESTAMPTZ,
  ADD COLUMN rotated_at TIMESTAMPTZ;

-- =====================================================================
-- INTEGRATIONS (VYB-0740–0743)
-- =====================================================================

ALTER TABLE integration_connection
  ADD COLUMN owns TEXT,                          -- what this system owns, e.g. 'test runs'
  ADD COLUMN direction TEXT NOT NULL DEFAULT 'INBOUND' CHECK (direction IN ('INBOUND','OUTBOUND','BOTH')),
  ADD COLUMN webhook_secret TEXT,                 -- shared secret for inbound HMAC verification (VYB-0741)
  ADD COLUMN failure_count INT NOT NULL DEFAULT 0,
  ADD COLUMN last_error TEXT,
  ADD COLUMN last_error_at TIMESTAMPTZ,
  ADD COLUMN degraded BOOLEAN NOT NULL DEFAULT false;

INSERT INTO integration_connection (key, connected, owns, direction) VALUES
 ('git', false, 'commits and code links', 'INBOUND'),
 ('ci', false, 'test runs', 'INBOUND'),
 ('hr', false, 'user directory / departures', 'INBOUND'),
 ('planning', false, 'delivery-tool push', 'OUTBOUND')
ON CONFLICT (key) DO NOTHING;

-- VYB-0741 AC2: a delivery id seen once is refused the second time. One row per
-- (integration, delivery id) rather than a cache — durable across a restart, and
-- the append-only audit table's own precedent for "never delete this kind of row."
CREATE TABLE webhook_delivery (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  integration_key TEXT NOT NULL REFERENCES integration_connection(key),
  delivery_id    TEXT NOT NULL,
  received_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (integration_key, delivery_id)
);

-- =====================================================================
-- AUDIT (VYB-0721/0723)
-- =====================================================================

-- V001's audit_event already has request_id and ip columns; the JPA entity mapping
-- them (and the code that populates ip) arrives in this phase — see AuditEvent.java
-- and RequestContext. Nothing to add here.

-- =====================================================================
-- SETTINGS (VYB-0702/0712/0713/0723/0731/0734)
-- =====================================================================

ALTER TABLE app_config
  -- VYB-0702 AC3: the maximum expiry duration a grant to an EXTERNAL user may carry.
  ADD COLUMN max_external_grant_days INT NOT NULL DEFAULT 90,
  -- VYB-0713 AC1: a service-account key older than this is reported stale.
  ADD COLUMN stale_key_age_days INT NOT NULL DEFAULT 90,
  -- VYB-0712 AC1: how long a rotated-out key/clientId keeps working.
  ADD COLUMN key_rotation_overlap_days INT NOT NULL DEFAULT 7,
  -- VYB-0618's sibling: how long an AI detector's dismissal-rate window looks back is
  -- unrelated to this — this is VYB-0723 AC1, how long an audit_event row is kept
  -- before it becomes eligible for archival.
  ADD COLUMN audit_retention_days INT NOT NULL DEFAULT 365,
  -- VYB-0731: authentication still succeeds while this is true; every other request
  -- is refused with a stated reason. Never touches a data row (AC2).
  ADD COLUMN suspended BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN suspended_reason TEXT;

-- =====================================================================
-- DETECTION — three new rules, all pure SQL over access_grant/app_user/service_account,
-- none of them needing an AI provider (VYB-0704/0705/0713 via the same reconciled
-- finding/dismiss/audit lifecycle every other detector already gets for free)
-- =====================================================================

INSERT INTO gap_rule_template (key,name,technique,severity,description,phase) VALUES
 ('rbac-sod','Separation of duties (grants)','GRAPH','crit','A user holds both an authoring role and APPROVER, or ADMINISTRATOR and APPROVER, at overlapping scope',5),
 ('departed-active','Departed account still active','RULE','crit','A user marked DEPARTED still holds an active, unrevoked grant',5),
 ('stale-key','Stale service-account key','RULE','high','A service account key is older than the configured age',5);
