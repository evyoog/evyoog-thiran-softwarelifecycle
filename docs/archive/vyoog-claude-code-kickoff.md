# Vyoog — Claude Code prompts

Three things live here:

1. **`CLAUDE.md`** — drop this at the repository root. Claude Code reads it automatically
   at the start of every session, so the rules below never need repeating.
2. **The kickoff prompt** — paste once, for session 1.
3. **The per-session prompt** — the template for every session after that.

A warning worth taking seriously: **do not paste the whole specification and ask for the
whole application.** A system this size will produce a plausible-looking 60% that
compiles and does nothing. The session protocol exists to prevent exactly that.

---

## 1. `CLAUDE.md` — repository root

````markdown
# Vyoog — agent working rules

## What this is
A requirements management platform. Spring Boot (Java 21) + React 18/TypeScript +
PostgreSQL 16 + Keycloak 25. The authoritative specification is
`docs/vyoog-build-specification.md`. The schema is the Flyway migrations in `backend/vyoog-domain/src/main/resources/db/migration`.
Read the relevant section of the spec before writing code. Do not infer requirements.

## Hierarchy
Platform → Product → App → Capability → Requirement

## Rules that are never negotiable

1. **Multi-tenancy.** Every tenant-owned table has `tenant_id` and RLS. The tenant comes
   from the JWT, never from a header, path or body the client controls. Bind it with
   `SET LOCAL` inside the transaction. If you add a table with `tenant_id`, you add its
   RLS policy and an isolation test in the same commit.
2. **No passwords.** Keycloak owns authentication. Never add a password column, a reset
   flow, or a field to set one.
3. **"Verified" is a predicate, not a flag.** A requirement is Verified only when a test
   passed against its *current* revision. Never store it as a boolean column.
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
- [ ] **Tenant-isolation test** if a tenant-scoped table was touched
- [ ] Flyway migration is forward-only and runs against a populated database
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
docker compose up -d             # postgres, keycloak, redis, minio
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
````

---

## 2. Kickoff prompt — session 1 only

> We are building **Vyoog**, a requirements management platform, from a written
> specification. This is session 1 of roughly 45.
>
> Read these three files completely before doing anything:
> - `docs/vyoog-build-specification.md`
> - ~~`docs/vyoog-schema.sql`~~ (removed, VYB-0905: the Flyway migrations are the schema)
> - `CLAUDE.md`
>
> **This session's scope is Phase 0 — Foundation, and nothing beyond it.** Do not start
> Phase 1. Do not create requirement CRUD. If you find yourself writing a controller for
> requirements, stop.
>
> Deliver, in this order:
>
> 1. A **Maven multi-module** skeleton exactly as laid out in §2.4 of the spec and
>    `docs/vyoog-maven-project-setup.md` — parent plus `vyoog-domain`, `vyoog-api`,
>    `vyoog-worker`, `vyoog-testkit` — with the Maven Wrapper committed, Spring Boot 3.3,
>    Java 21, and ArchUnit tests enforcing the §2.1 package boundaries.
> 2. `docker-compose.yml` with Postgres 16 (with `pgvector`), Keycloak 25, Redis and
>    MinIO, plus a Keycloak realm import for the `vyoog` realm defined in §4.2.
> 3. Flyway `V001__baseline.sql` (originally taken from the now-removed `docs/vyoog-schema.sql`), applied by a
>    `vyoog_migrator` role that is distinct from the application's `vyoog_app` role.
> 4. The tenancy layer from §3.3: a `TenantContext`, a `DataSource` decorator that issues
>    `SELECT set_config('app.tenant_id', ?, true)` at transaction start, and Hibernate
>    `@TenantId` on a single sample entity.
> 5. **The tenant-isolation integration test**, in `vyoog-testkit` and named
>    `TenantIsolationIT`. With no tenant bound, a query against a tenant-scoped table
>    must return **zero rows**. This is the exit criterion for the whole phase — write it
>    first if you prefer.
> 6. Spring Security configured as an OAuth2 resource server validating Keycloak JWTs:
>    issuer, audience, signature via JWKS, and an `app_user` upsert on first sight of a
>    subject.
> 7. A React 18 + TypeScript + Vite application with the design tokens ported verbatim
>    from the prototype, the application shell (sidebar, ten modules, divider before
>    Administration, collapsible, dark and light themes), routing, and OIDC login with
>    Authorization Code + PKCE.
> 8. `BUILD-REGISTER.md` seeded with the Phase 0 requirement IDs.
>
> **Exit criterion:** I can run `docker compose up`, start the API and the web app, log
> in through Keycloak, land on an empty Home screen, and `cd backend && ./mvnw -B verify` passes
> including `TenantIsolationIT`.
>
> Work in small commits, each with a `Requirement:` trailer. When you are done, append
> the session audit block to `BUILD-REGISTER.md`:
>
> ```
> ## Session 1 — Phase 0 Foundation
> Completed:  <ids>
> Not done:   <ids and why>
> Discovered: <anything the spec did not anticipate>
> ```
>
> Before you start, tell me your plan and any question where the spec is ambiguous.
> Do not begin coding until I confirm.

---

## 3. Per-session prompt — every session after the first

> Session **N**. Read `CLAUDE.md` and `BUILD-REGISTER.md` first, then §**X** of
> `docs/vyoog-build-specification.md`.
>
> **Scope for this session, and nothing else:** *<one capability, e.g. "the requirement
> state machine and its transition endpoint">*
>
> Requirement IDs in scope: `VYB-nnnn` … `VYB-nnnn`
>
> Before coding:
> 1. Restate the scope in your own words so I can check we agree.
> 2. Generate the full requirement text for these IDs only — one paragraph plus
>    acceptance criteria each. Do not generate text for IDs outside this session.
> 3. List anything ambiguous. Wait for my answer.
>
> Then implement, honouring the Definition of Done in `CLAUDE.md` for every item.
>
> Finish by appending the session audit block to `BUILD-REGISTER.md`:
> **Completed / Not done (with reasons) / Discovered**.
>
> If you run short of context before the scope is complete, stop, write the audit block
> honestly, and say what remains. **Do not** compress or skip acceptance criteria to make
> the session look finished. A half-finished session that reports itself accurately is
> worth more than a complete-looking one that lied.

---

## 4. `BUILD-REGISTER.md` — the completeness mechanism

Seed it in session 1, one row per requirement, all ≈540 up front. Fill the text in
just-in-time, per session.

```markdown
# Vyoog build register

| ID | Phase | Capability | One-line requirement | Status | Session |
|---|---|---|---|---|---|
| VYB-0001 | 0 | Tenancy | Bind tenant from JWT at transaction start | DONE | 1 |
| VYB-0002 | 0 | Tenancy | Unbound query returns zero rows | DONE | 1 |
| VYB-0003 | 0 | Identity | Upsert app_user on first token | DONE | 1 |
| VYB-0004 | 0 | Shell | Sidebar hides modules the user has no grant for | TODO | |
| ... | | | | | |
```

Status vocabulary, deliberately small: `TODO` · `IN PROGRESS` · `DONE` · `BLOCKED` ·
`DESCOPED` (with a reason in the session audit).

A CI check should fail the build when a commit carries no `Requirement:` trailer. It is
five lines of shell and it is the only thing that reliably stops drift over forty
sessions.

---

## 5. Session plan

Roughly 45 sessions. Adjust as reality intervenes, but keep one capability per session.

| Sessions | Phase | Focus |
|---|---|---|
| 1–4 | 0 | Foundation, tenancy, Keycloak, shell |
| 5–12 | 1 | Portfolio, requirement CRUD + revisions + state machine, the data grid, detail panel, create screen, trace links, closure table, detectors 1/3/4/5/6 + ambig |
| 13–20 | 2 | Reviews, signatures, step-up, approval with SoD, test ingest, verification, defects, derived tasks, clarification, git webhook, detector 8 |
| 21–28 | 3 | Briefs (three targets, developer assignment, staleness), scope signals, impact, baselines, releases, variants, deployments, release notes, design module |
| 29–35 | 4 | pgvector, embeddings, detectors 7/9/10/11/12, Analytics complete, import queue |
| 36–42 | 5 | Administration, grants, service accounts, security findings, audit UI, tenant export |
| 43–45 | — | Performance, accessibility, load testing, hardening |

---

## 6. Anti-patterns to name explicitly

Tell the agent these directly. Each is a failure mode observed on projects this size.

- **Breadth-first generation.** Producing every controller as a stub and no working
  vertical slice. Insist on one capability working end to end before the next begins.
- **Silent descoping.** Dropping an acceptance criterion because the session is running
  long. The audit block exists to make this visible; enforce that it is honest.
- **Reinventing the grid.** The data grid has a dozen behaviours (§7.4.1). It is several
  sessions on its own. Do not let it be squeezed into a corner of another session.
- **Storing derived state.** A `verified` boolean, a `gap_count` column, a `coverage`
  percentage. All three are predicates over other tables. Storing them guarantees they
  will drift.
- **Forgetting the tenant on a new table.** The RLS loop in the schema catches this at
  migration time only if the column is named `tenant_id`. Keep the name exact.
- **Skipping the isolation test** because "the filter is obviously there". The whole
  point is the case where it is obviously there and is not.

---

*Companion file: `vyoog-build-specification.md`.*
