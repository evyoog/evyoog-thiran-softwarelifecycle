# AI usage and token budgets

Added by VYB-0939 (Phase 6, Sprint 8, F29 and F30). Migration `database/migrations/V050__ai_usage_ledger.sql`. Code: `com.vyoog.ai` (`MeteredModelGateway`, `AiUsageLedger`, `AiBudgetService`, `AiUsageReport`, `ChatRequest.promptVersion`). Decision: D32. It sits in front of the redacting gateway ([`../security/ai-redaction.md`](../security/ai-redaction.md)) and the provider gateway ([`ai-model-gateway.md`](ai-model-gateway.md)).

## What is recorded

**One row in `ai_call` for every model call** that reaches the point of being made:

| Column | Meaning |
|---|---|
| `called_at` | When it was made. |
| `purpose` | What it was for, fixed by the caller: `rewrite-suggestion`, `test-case-suggestion`, `brief-analysis`, `brief-elaboration`, `document-triage`, `document-synthesis`, `document-critique`, `trace-relation`, `conflict-adjudication`, `embedding`. A new caller names its own; the column is not an enum. |
| `prompt_version` | The first 8 hex characters of the SHA-256 of the **system prompt**. Change a prompt and the version changes; the usage screen then shows the two side by side. `n/a` for an embedding, which has no prompt. |
| `endpoint`, `model`, `call_kind` | `CHAT` or `EMBEDDINGS`; the model the provider says it used (the configured one if it does not say); `INTERACTIVE` or `BATCH`. |
| `outcome` | `OK`, `FAILED` (the provider or the gateway refused or failed; retries are inside one call), or `BUDGET_REFUSED` (stopped by the limit before anything was sent). |
| `prompt_tokens`, `completion_tokens`, `total_tokens` | **As the provider reported them.** Null when it reported none; never estimated. Only an `OK` call has a total. |
| `duration_ms` | How long the call took, retries included. |

**Not recorded:** the prompt text, the reply text, the requirement or any person. There is no user column. This is deliberate (CLAUDE.md rule 7, D32): usage is by purpose, prompt version and model, never by person. A database check keeps a total equal to prompt plus completion tokens and null for a call that did not succeed.

Nothing is recorded when AI is not configured: a switched-off feature is not a failed call.

A refusal for the budget is recorded **at most once a minute per purpose**, so a sweep refused a thousand times leaves a handful of rows. The counter `ai.budget.refusals` counts every one. Also exported: `ai.calls{purpose,outcome}` and `ai.tokens{purpose}`.

## Budgets

An administrator sets, in Administration, Settings, **AI token budget** (`PUT /api/v1/settings/ai-token-budget`, body `{daily, monthly}`):

- a limit per **UTC day** and a limit per **UTC calendar month**; either may be empty (no limit); both are replaced together;
- the **tighter** one decides: a call is refused as soon as either is reached;
- a limit is **a number of tokens above zero** (zero or less is a 400); it is never money and nothing converts tokens to a price.

When a limit is reached, `MeteredModelGateway` refuses the call with an `AiProviderUnavailableException`, which every caller already handles as "AI is not available": *"The AI token budget for today is used up (X of Y tokens). It resets at 00:00 UTC, or an administrator can raise it in Administration, Settings."* (or "for this month ... resets on 2026-11-01 00:00 UTC"). Nothing is sent to the provider. No stand-in answer is made.

Each change is audited as `settings.ai-token-budget-changed` with the old and new limits.

### How the used figure is known

The used tokens are summed from the ledger and held for **10 seconds**, plus what this instance has recorded since, so its own calls count at once. Consequences, accepted:

- A call already in flight when the limit is reached finishes, so **usage can pass a limit by what is in flight**.
- With several application instances, another instance's calls are seen within 10 seconds.
- A call whose tokens the provider did not report counts as zero.
- The day and month roll over at 00:00 UTC; the held figure is dropped when the date changes.

## The usage screen

**Analytics, AI usage** (any signed-in person): today's and this month's tokens beside their limits (with a word and a glyph for no limit, within, near at 80 percent, used up; never amber), a chart of the last 30 days, and the month by purpose, prompt version and model (calls, failed, refused, prompt, reply and total tokens, share). `GET /api/v1/ai/usage/summary?days=30` (1 to 366). There is no per-person view and no money anywhere on it. Absence reads in words ("No limit is set", "No AI calls this month").

## Not here

- **No purge of `ai_call`.** One row a call grows without bound; a retention job is a later decision.
- **The ledger row is written in its own transaction** (so a call that was made is on record even if the caller's transaction rolls back). A caller that makes an AI call *inside* a database transaction holds a second connection while it does so; moving the calls out of transactions is VYB-0940. A failure to write the row is logged and never thrown.
- The old per-sweep call limit (`ai_calls_per_run_limit`, "AI usage, this sweep") is unchanged and separate: it limits calls per sweep, this limits tokens per period.
- Embeddings share the one purpose `embedding`.

## Tests

`MeteredModelGatewayTest` (`VYB0939_AC1` to `AC8`), `AiUsageLedgerTest` (`AC9` to `AC11`), `ChatRequestPromptVersionTest` (`AC12`), `ModelGatewayWiringIT` (`AC13` to `AC19`: rows with tokens from a stub provider, no prompt or user column, refusal at the daily and monthly limit with nothing sent, administrator-only setting with its audit event, usage open to any signed-in person with no per-person or money field), `aiUsage.test.ts` (`AC20` to `AC23`).
