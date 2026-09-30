<!-- Split verbatim from the former backend/docs/vyoog-build-specification.md (lines 68–90). Section numbers are unchanged; docs/02-requirements/SPECIFICATION-INDEX.md maps every section to its file. -->

### 1.4 Non-negotiable product principles

These are load-bearing. Violating them produces a different, worse product.

| # | Principle | Consequence for implementation |
|---|---|---|
| 1 | **Twelve gap classes.** The first four need no AI — they are pure graph queries. | Ship v1 with GRAPH + RULE detectors only. AI is Phase 4. |
| 2 | **Gap visibility is ambient, not a destination.** | Coverage pips `U D C T` on every grid row; gaps surface in place, not only in Analytics. |
| 3 | **Amber means AI, and only AI.** Never a status colour. | Status uses green/red/blue/grey. Anything AI-derived is amber. Enforce in the design tokens. |
| 4 | **Borrowed data looks borrowed.** | Anything Vyoog displays but does not own renders hatched/dashed with a source tag. Absence renders as "not connected", never as blank or zero. |
| 5 | **VERIFIED is not a requirement status (D16).** | APPROVED is the terminal status of the requirement lifecycle. Test evidence (a linked test's pass/fail against the *current* revision) is still tracked, strictly, and any revision bump still invalidates it automatically — but it is a quality-reporting predicate (`requirement_verification_state.is_verified`), never a value `requirement.status` can hold. "Verifying" is a person's action on an IN_REVIEW requirement (→ REVIEWED), not something CI reports back automatically. |
| 6 | **Tasks are derived, never authored.** | There is no "create task" endpoint. Tasks are a query over requirement state. |
| 7 | **AI proposes, the human decides.** | No AI output is ever applied automatically. Every suggestion has an explicit accept/dismiss, and dismissals are stored with a reason. |
| 8 | **Vyoog never stores a password.** | Keycloak owns authentication. There is no password column, no password reset flow, no field to set one. |
| 9 | **Access is granted as role × scope.** | Not "Reviewer" but "Reviewer on Valam ▸ HR Intelligence". See §4.4. |
| 10 | **Vyoog holds no money and no hours.** | No cost tables, no timesheets, no per-person productivity metrics, no combined predicted-effort figure, no Gantt. Finance and delivery tools own these. |

Principle 10 is a deliberate scope decision made during design review. Cost, budget and
initiative tracking were built and then **removed**. Do not reintroduce them without an
explicit decision — they are listed in §13 as out of scope.

---
