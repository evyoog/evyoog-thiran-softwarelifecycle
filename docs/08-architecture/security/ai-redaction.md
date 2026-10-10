# AI redaction: what leaves the system

Added by VYB-0937 (Phase 6, Sprint 8, F27). Migration `database/migrations/V048__ai_redaction_settings.sql`. Code: `com.vyoog.ai` (`Redactor`, `Redaction`, `DataClass`, `RedactingModelGateway`, `RedactionSettings`, `KnownPeople`). Decision: D30. It sits in front of the model gateway ([`../backend-architecture/ai-model-gateway.md`](../backend-architecture/ai-model-gateway.md)).

## The rule

**Every model call is cleaned before it is sent.** `RedactingModelGateway` sits between what every class is handed (`MeteredModelGateway`, which is `@Primary`, VYB-0939) and the OpenAI gateway; neither of those two is injected anywhere else. It cleans:

- the **user text** of a chat call (the data a caller sends), and
- the **text of an embedding** (so the vector is made from clean text too; nothing needs restoring, only a vector comes back).

The **system prompt** is a caller's own fixed instruction and is not touched. A caller must never put data in it.

## What is taken out

| Kind | What it finds | What happens | Can an administrator switch it off? |
|---|---|---|---|
| `SECRET` | Private-key blocks; AWS, GitHub, Slack, Google, Stripe and `sk-` keys; JWTs; `Bearer` tokens; the user and password in a URL; the value of a `password`, `secret`, `token`, `api key` (and similar) assignment when it looks like a credential (6 or more characters with a digit or symbol) | Replaced by `[REDACTED-SECRET]`. **Never restored.** | **No.** Refused by the API (400) and by a database constraint. |
| `EMAIL` | Email addresses | Replaced by `[EMAIL_n]`, put back in the reply | Yes |
| `PHONE` | `+` international; `(415) 555-2671`; `415-555-2671`; `98765 43210` | `[PHONE_n]` | Yes |
| `CARD` | 13 to 19 digits (spaces or dashes allowed) that pass the Luhn check | `[CARD_n]` | Yes |
| `IP_ADDRESS` | IPv4 | `[IP_n]` | Yes |
| `PERSON` | The display names of the people in this system's user table, whole words, any case, longest first | `[PERSON_n]` | Yes |

The same value in one call gets the same token, so the provider can tell two mentions are one thing. Each spelling is its own token and comes back as it was written.

## What it does not find (stated limits)

- **A name of someone who is not in the user table.** That needs a name-recognition model; D30 chose not to add one.
- A **bare run of digits** is not a phone number (a requirement is full of numbers); phone numbers are found in the four shapes above only.
- **IPv6**, bank account numbers and national ID numbers are not looked for.
- A **version number** written like an address ("1.2.3.4") is taken as an IP address.
- A credential with no assignment, no known prefix and no URL around it (a bare random string) is not recognised.
- A person added in the last minute is not matched yet (the list of names is held for 60 seconds).

## The reply

Tokens in the provider's reply are replaced by the original values. If the reply is JSON (the call asked for it, or the reply starts with `{` or `[`), each value goes back in as a JSON string would, so a quote in a name cannot break the document the caller parses. A token the provider invented is left as written. A secret is never put back.

## Fails closed

If the text cannot be checked (the redactor or the list of people fails), **nothing is sent** and the call is refused like any other provider failure (`AiProviderUnavailableException`). A failure is never a reason to send more.

## The opt-out

`app_config.ai_redaction_disabled` (a list of kinds; empty, the default, means all on). An administrator changes it under **Administration, Settings, "Before text goes to the AI provider"** (`PUT /api/v1/settings/ai-redaction`, administrator only). The whole list is replaced; an unknown kind or `SECRET` is refused with 400. Every change records `settings.ai-redaction-changed` with the list before and after. It takes effect on the next model call.

## What is recorded

Counts per kind and endpoint, never a value: the metric `ai.redactions{class,endpoint}` and one INFO log line per call that redacted something. No audit event is written per call (it is not a state change).

## Tests

`RedactorTest` (`VYB0937_AC1` to `AC7`: every secret shape, prose that is not a secret, each class, opt-out, restore, escaping, about 1.3 MB of text and 0.7 MB of hostile input inside a 10 s bound), `RedactingModelGatewayTest` (`AC8` to `AC15`: what is sent, the reply, embeddings, opt-out, fail closed, counts), `ModelGatewayWiringIT` (`VYB0936_AC16`, `VYB0937_AC16` to `AC19`: the real application against a stub provider, the settings endpoint with real tokens, the audit event, the database constraint), `aiRedaction.test.ts` (`AC20`).
