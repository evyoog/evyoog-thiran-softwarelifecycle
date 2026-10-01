# Vyoog — agent working rules

## What this is
A requirements management platform. Spring Boot (Java 21) + React 18/TypeScript +
PostgreSQL 16 + Keycloak 25. The authoritative specification is
the build specification, split by topic under `docs/` (`docs/02-requirements/SPECIFICATION-INDEX.md` maps
every section number to its file). The schema is the Flyway migrations in `database/migrations`.
Read the relevant requirements under `docs/` before writing code. Do not infer requirements, and do
not implement anything that is not defined or approved there.

## Where things live (source of truth)
| What | Where |
|---|---|
| Requirements, status and their tests | `docs/02-requirements/` (generated from `BUILD-REGISTER.md`, which is the source) |
| Business rules, gap detection | `docs/03-business-rules/` |
| Workflows, requirement state machine | `docs/04-workflows/` |
| Screens and frontend conventions | `docs/05-ui/`, `docs/02-requirements/FRD/<feature>/ui-requirements.md` |
| API design | `docs/06-api/` |
| Data model and database rules | `docs/07-database/`; the schema itself is `database/migrations/` |
| Architecture, security | `docs/08-architecture/` |
| Decisions | `docs/DECISIONS.md` |
| Test cases | automated: next to the code, named `VYBnnnn_ACn_...`; manual and UAT: `test-cases/` |
| Deployment | `deployment/`; local services `docker-compose.yml` |
| CI/CD | `.github/workflows/` |

Approved requirements are committed under `docs/`; do not leave them only in a chat or a Claude
Project. Do not put business requirements inside `frontend/` or `backend/` source folders. A change to
a rule, state machine, API or data model is a change to `docs/` in the same pull request.

## Hierarchy
Platform → Product → App → Capability → Requirement

## Rules that are never negotiable

1. **Schema isolation, not multi-tenancy.** Vyoog is single-tenant by design (see
   `docs/DECISIONS.md` D3). It lives in its own Postgres schema (`vyg_requirement`) on
   the same shared RDS instance as the company's other applications — vyg-pms in
   `vyoog_pms`, the pricing tool in its own schema — exactly their pattern. There is no
   `tenant_id` column and no row-level security anywhere in this schema. Do not add
   either back without a new decision recorded in `docs/DECISIONS.md`; a stray
   `tenant_id` column here is a mistake, not a convention to follow.
2. **No passwords.** Keycloak owns authentication (the shared `eVyoog` realm — same as
   vyg-pms and the pricing tool). Never add a password column, a reset flow, or a field
   to set one.
3. **VERIFIED is not a requirement status (D16, VYB-0810/0811).** It used to be — a strict,
   system-only predicate set only when a test passed against a requirement's *current*
   revision — but that coupling was deliberately removed. APPROVED is now the terminal
   status of the requirement lifecycle; "verifying" is a decision a person makes (the
   "Verify" action on an IN_REVIEW requirement, landing on REVIEWED), not something CI
   reports back automatically. Test evidence itself is unaffected and still real: the
   `verification`/`test_case`/`test_run` tables, `VerificationService`, and the CI
   ingestion endpoint (`POST /api/v1/ci/test-runs`) all still exist for quality
   reporting — a pass or fail is still recorded against a requirement's revision, and
   `requirement_verification_state.is_verified` is still a live predicate over that
   evidence. None of it may ever again write to `requirement.status`. Do not reintroduce
   a VERIFIED (or renamed-but-equivalent) requirement status without a new decision
   recorded in `docs/DECISIONS.md`.
4. **Tasks are derived.** There is no task table and no create-task endpoint. D19
   carves one narrow, explicitly-confirmed exception: `task_completion` (V034) records
   that a person dismissed one derived task, at the object's revision at the time —
   it never creates, blocks, or authors a task, and a dismissal that's gone stale
   (the object's revision has since moved on) stops suppressing anything on its own.
   Do not extend this pattern elsewhere without its own decision.
5. **Amber means AI.** Never use the amber token for a status. D19 carves one narrow
   exception, confirmed explicitly: My Work's "Due today" task group uses `--ai`
   (matching the reference prototype). Nowhere else in the app does, and reusing it
   for another status needs its own decision, not this one.
6. **AI proposes, the human decides.** No AI output is ever applied automatically.
7. **No money, no hours.** No cost, budget, effort estimate, velocity or per-person
   productivity anywhere. Finance and the delivery tool own those.
8. **Borrowed data looks borrowed.** Anything from an external system renders hatched
   with a source tag. Absence renders "not connected", never blank or zero.
9. **No real credentials in the repo.** Database, Keycloak client and SSO secrets come
   from environment variables. Defaults in `application.yml` are empty or obviously
   fake. The app must fail at startup with a clear message when a required secret is
   missing (D22, proposed; it supersedes D9 and D20, which committed real defaults).

## Definition of Done — a task is not finished until all of these hold

- [ ] Unit tests for the logic
- [ ] Integration test against real Postgres via Testcontainers if SQL was touched
- [ ] Flyway migration is forward-only, targets the `vyg_requirement` schema, and runs
      against a populated database
- [ ] `cd backend && ./mvnw -B verify` is green
- [ ] OpenAPI regenerated; frontend types regenerated (once `generate-api` exists, Sprint 2, VYB-0912)
- [ ] An audit event is emitted for every state change
- [ ] Commit trailer present: `Requirement: VYB-nnnn`
- [ ] Test named `VYBnnnn_ACn_shortDescription`
- [ ] `BUILD-REGISTER.md` row updated, and `python3 scripts/generate-requirements-docs.py` run so `docs/02-requirements/` matches it
- [ ] Every database structure change is a new forward-only migration in `database/migrations/`
- [ ] No new `TODO` without a linked issue
- [ ] Every new or changed write endpoint has an explicit role rule and a test that proves an unauthorised user gets 403 (annotate it `@RequiresAccess` and add it to `AccessPolicyTest.EXPECTED`; see `docs/08-architecture/security/access-rules.md`)
- [ ] No test or runner can reach a non-local database (tests use Testcontainers or the docker-compose database)
- [ ] CI is green on the pull request

## Commands
```bash
cd backend && ./mvnw -B verify                 # compile + unit (Surefire) + IT (Failsafe) + ArchUnit
cd backend && ./mvnw -B test                   # unit only, fast loop
cd backend && ./mvnw -B -pl vyoog-api spring-boot:run
cd backend && ./mvnw -B -pl vyoog-api spring-boot:build-image
docker compose up -d                 # (repository root) local postgres, redis, minio (Keycloak is the shared eVyoog realm — no local instance)
cd frontend && npm run dev
cd frontend && npm run test
# `npm run generate-api` (OpenAPI → TypeScript) does not exist yet; Sprint 2 adds it (VYB-0912).
```

Integration tests are named `*IT.java` and run under Failsafe, not Surefire. A test that
touches Postgres and is named `*Test` will run in the wrong phase — this is the most
common Maven mistake on this project.

**Current state:** no `*IT.java` tests exist yet, including the `FoundationSmokeIT` that
older docs mention. The requirement stands; Sprint 2 adds them (VYB-0907). Until then
`verify` runs unit tests and ArchUnit only.

## Sprint work
The plan is in `BUILD-REGISTER.md`, Phase 6 (rows VYB-0900 onwards). One register row per session. Read the row and its finding IDs before you start. Do not start a row from a later sprint. Do not change scope. If the row is bigger than one session, stop and say how you would split it.
The branch is `sprint/sNN-<name>`. One pull request per row. Commit trailer: `Requirement: VYB-nnnn`.
Never run anything against the production database or call the production Keycloak.

## Architecture boundaries
Modular monolith. `com.vyoog.<module>`. A module may not import another module's
`internal` package. Cross-module communication goes through `api` packages or Spring
application events. Only `platform` is importable by everyone. ArchUnit enforces this —
if `archTest` fails, fix the design, not the test.

## Style
- Maven: versions only in the parent `dependencyManagement`. Never a `<version>` in a
  child POM for a managed dependency.
- Java: constructor injection only, no field injection. No Lombok on domain entities.
- Records for DTOs. Entities are classes.
- Recursive CTEs go in jOOQ or JdbcTemplate, never JPQL.
- React: TanStack Query for all server state. No Redux. Access token in memory only,
  never `localStorage`.
- TypeScript strict. API types are generated, never hand-written.

## When you are unsure
Stop and ask. A wrong assumption compounds across sessions. Specifically: never invent a
requirement, never guess at a business rule, never add a field the spec does not mention.
