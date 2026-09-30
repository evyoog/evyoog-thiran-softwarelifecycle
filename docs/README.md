# Vyoog documentation

The repository is the single source of truth for what SWLCA (Vyoog, the SW Life Cycle Application) must do. Approved requirements, business rules, workflows, API and data design live here, next to the code and tests that implement them, so their history is reviewable and a feature branch can change all of them together.

## Where to look

| To find | Go to |
|---|---|
| Every requirement, by feature, with status and tests | [`02-requirements/README.md`](02-requirements/README.md) |
| Planned requirements (Phase 6) | [`02-requirements/functional-requirements/phase-6-planned.md`](02-requirements/functional-requirements/phase-6-planned.md) |
| The full build specification, by section | [`02-requirements/SPECIFICATION-INDEX.md`](02-requirements/SPECIFICATION-INDEX.md) |
| Product vision, scope, phases, open questions | [`01-business/`](01-business/) |
| Business rules and gap-detection rules | [`03-business-rules/`](03-business-rules/) |
| Workflows and the requirement state machine | [`04-workflows/`](04-workflows/) |
| Screens and frontend conventions | [`05-ui/`](05-ui/) |
| API design and OpenAPI | [`06-api/`](06-api/) |
| Data model and database design | [`07-database/`](07-database/) |
| Architecture, security, deployment runbooks | [`08-architecture/`](08-architecture/) |
| Integrations | [`09-integrations/`](09-integrations/) |
| User manual, release notes | [`10-user-manual/`](10-user-manual/), [`11-release-notes/`](11-release-notes/) |
| Every decision and why | [`DECISIONS.md`](DECISIONS.md) |
| Secrets rotation | [`SECRETS-ROTATION.md`](SECRETS-ROTATION.md) |
| Early bootstrap notes, kept for history | [`archive/`](archive/) |

## Rules

- Approved requirements are committed here. Do not leave them only in a Claude Project, a chat or a ticket.
- Do not put business requirements inside `frontend/` or `backend/` source folders.
- The live status of every requirement is [`BUILD-REGISTER.md`](../BUILD-REGISTER.md) at the repository root. The per-feature pages under `02-requirements/` are generated from it by `scripts/generate-requirements-docs.py`; edit the register, not the pages. CI fails if they are out of date.
- A change to a rule, state machine, API or data model is a change to a file here in the same pull request as the code.
- New decisions go in [`DECISIONS.md`](DECISIONS.md) with a number.

## Flow

Approved requirement → `docs/` → GitHub issue → feature branch (`feature/VYB-nnnn-name`, one row of the register per branch) → code + database migration + tests + docs together → pull request → CI → UAT → release. Every commit carries a `Requirement: VYB-nnnn` trailer, and tests are named `VYBnnnn_ACn_shortDescription`, which is how a requirement is traced to its tests ([`test-cases/automated-tests-index.md`](../test-cases/automated-tests-index.md)).
