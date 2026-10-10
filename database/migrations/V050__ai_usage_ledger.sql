-- VYB-0939 (F29, F30): every model call is one row here, and an administrator can cap the tokens used per day and per month.
-- A row records what the call was for, which version of the prompt, which model, how many tokens the provider REPORTED
-- (null when it said nothing; never estimated), how long it took and how it ended. It holds no prompt or reply text and no
-- user: usage is reported by purpose, prompt version and model, never by person (CLAUDE.md rule 7, D32).
CREATE TABLE ai_call (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  called_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  purpose           TEXT NOT NULL CHECK (btrim(purpose) <> ''),
  prompt_version    TEXT NOT NULL CHECK (btrim(prompt_version) <> ''),   -- first 8 hex of the system prompt's SHA-256; 'n/a' for embeddings
  endpoint          TEXT NOT NULL CHECK (endpoint IN ('CHAT', 'EMBEDDINGS')),
  model             TEXT NOT NULL CHECK (btrim(model) <> ''),
  call_kind         TEXT NOT NULL CHECK (call_kind IN ('INTERACTIVE', 'BATCH')),
  outcome           TEXT NOT NULL CHECK (outcome IN ('OK', 'FAILED', 'BUDGET_REFUSED')),
  prompt_tokens     INT CHECK (prompt_tokens >= 0),
  completion_tokens INT CHECK (completion_tokens >= 0),
  total_tokens      INT CHECK (total_tokens >= 0),
  duration_ms       INT NOT NULL CHECK (duration_ms >= 0),
  CHECK (outcome = 'OK' OR total_tokens IS NULL),
  CHECK (total_tokens IS NULL OR total_tokens = COALESCE(prompt_tokens, 0) + COALESCE(completion_tokens, 0))
);
CREATE INDEX ai_call_called_at_idx ON ai_call (called_at DESC);
CREATE INDEX ai_call_purpose_idx ON ai_call (purpose, called_at DESC);

-- A cap on tokens, never on money. Null means no cap. The tighter of the two applies.
ALTER TABLE app_config ADD COLUMN ai_token_budget_daily BIGINT CHECK (ai_token_budget_daily > 0);
ALTER TABLE app_config ADD COLUMN ai_token_budget_monthly BIGINT CHECK (ai_token_budget_monthly > 0);
