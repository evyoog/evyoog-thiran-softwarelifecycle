-- VYB-0937: which kinds of personal data an administrator has switched redaction OFF for, before text goes to a model
-- provider. Empty (the default) means every class is on. SECRET is deliberately not a value that can be stored: secrets are
-- always removed and cannot be opted out, and the constraint makes that true for any writer, not only the API.
ALTER TABLE app_config ADD COLUMN ai_redaction_disabled TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE app_config ADD CONSTRAINT app_config_ai_redaction_disabled_chk
  CHECK (ai_redaction_disabled <@ ARRAY['EMAIL', 'PHONE', 'CARD', 'IP_ADDRESS', 'PERSON']::text[]);
