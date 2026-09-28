# Vyoog — agent working rules

## What this is
A requirements management platform. Spring Boot (Java 21) + React 18/TypeScript +
PostgreSQL 16 + Keycloak 25. The authoritative specification is
`docs/vyoog-build-specification.md`. The schema is `docs/vyoog-schema.sql`.
Read the relevant section of the spec before writing code. Do not infer requirements.

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
4. **Tasks are derived.** There is no task table and no create-task endpoint.
5. **Amber means AI.** Never use the amber token for a status.
6. **AI proposes, the human decides.** No AI output is ever applied automatically.
7. **No money, no hours.** No cost, budget, effort estimate, velocity or per-person
   productivity anywhere. Finance and the delivery tool own those.
8. **Borrowed data looks borrowed.** Anything from an external system renders hatched
   with a source tag. Absence renders "not connected", never blank or zero.

## Definition of Done — a task is not finished until all of these hold

- [ ] Unit tests for the logic
- [ ] Integration test against real Postgres via Testcontainers if SQL was touched
- [ ] Flyway migration is forward-only, targets the `vyg_requirement` schema, and runs
      against a populated database
- [ ] `cd backend && ./mvnw -B verify` is green
- [ ] OpenAPI regenerated; frontend types regenerated
- [ ] An audit event is emitted for every state change
- [ ] Commit trailer present: `Requirement: VYB-nnnn`
- [ ] Test named `VYBnnnn_ACn_shortDescription`
- [ ] `BUILD-REGISTER.md` row updated
- [ ] No new `TODO` without a linked issue

## Commands
```bash
cd backend && ./mvnw -B verify                 # compile + unit (Surefire) + IT (Failsafe) + ArchUnit
cd backend && ./mvnw -B test                   # unit only, fast loop
cd backend && ./mvnw -B -pl vyoog-api spring-boot:run
cd backend && ./mvnw -B -pl vyoog-api spring-boot:build-image
docker compose up -d             # local postgres, redis, minio (Keycloak is the shared eVyoog realm — no local instance)
cd frontend && npm run dev
cd frontend && npm run test
cd frontend && npm run generate-api   # OpenAPI → TypeScript
```

Integration tests are named `*IT.java` and run under Failsafe, not Surefire. A test that
touches Postgres and is named `*Test` will run in the wrong phase — this is the most
common Maven mistake on this project.

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
