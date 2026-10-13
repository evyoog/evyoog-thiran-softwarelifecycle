-- VYB-0955 (F21, D47): who is told what, and how. The inbox already existed (V001, V008). This adds a person's own choice of which kinds
-- of notification they want in the inbox and by email, the administrator's choice of which kinds are posted to the team chat channel,
-- and the queue that email and chat go out through.
--
-- notification_preference: a row is an explicit choice; no row means "the kind's default" (the default lives in the code, with the
-- kind), so adding a kind later changes nothing for anyone who has already chosen.
CREATE TABLE notification_preference (
  user_id    UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  kind       TEXT NOT NULL,
  channel    TEXT NOT NULL CHECK (channel IN ('INBOX', 'EMAIL')),
  enabled    BOOLEAN NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, kind, channel)
);

-- notification_chat_kind: the kinds the administrator has switched on for the chat channel. A row is "on"; chat is a channel for the
-- team, not a person, so it is not a personal preference. The webhook address itself is a secret and lives in the environment.
CREATE TABLE notification_chat_kind (
  kind       TEXT PRIMARY KEY,
  enabled_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
  enabled_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX notification_chat_kind_enabled_by_idx ON notification_chat_kind (enabled_by);

-- notification_delivery: one row per email to one person, or per post to the chat channel, written in the same transaction as the
-- change that caused it and sent afterwards by a scheduled job. It carries its own copy of the words so that it can be sent, or
-- shown as failed, whatever has happened to the inbox row since. Urgent email is due at once; the rest waits out the digest window and
-- goes as one message per person. Chat rows with the same dedupe_key are one post (for example, every failing test in one run).
CREATE TABLE notification_delivery (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  channel         TEXT NOT NULL CHECK (channel IN ('EMAIL', 'CHAT')),
  kind            TEXT NOT NULL,
  user_id         UUID REFERENCES app_user(id) ON DELETE CASCADE,          -- the recipient of an email; null for chat
  notification_id UUID REFERENCES notification(id) ON DELETE SET NULL,
  title           TEXT NOT NULL,
  subtitle        TEXT,
  link            TEXT,
  urgent          BOOLEAN NOT NULL DEFAULT false,
  dedupe_key      TEXT,
  state           TEXT NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'SENT', 'FAILED')),
  attempts        INT NOT NULL DEFAULT 0,
  last_error      TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  due_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  sent_at         TIMESTAMPTZ,
  CHECK ((channel = 'EMAIL' AND user_id IS NOT NULL) OR (channel = 'CHAT' AND user_id IS NULL))
);
CREATE INDEX notification_delivery_due_idx ON notification_delivery (due_at) WHERE state = 'PENDING';
CREATE INDEX notification_delivery_user_id_idx ON notification_delivery (user_id);
CREATE INDEX notification_delivery_notification_id_idx ON notification_delivery (notification_id);
CREATE INDEX notification_delivery_created_at_idx ON notification_delivery (created_at);
CREATE UNIQUE INDEX notification_delivery_dedupe_idx ON notification_delivery (channel, dedupe_key) WHERE dedupe_key IS NOT NULL;
