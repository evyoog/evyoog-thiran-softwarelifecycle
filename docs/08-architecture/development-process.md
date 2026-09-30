<!-- Split verbatim from the former backend/docs/vyoog-build-specification.md (lines 1468–1551). Section numbers are unchanged; docs/02-requirements/SPECIFICATION-INDEX.md maps every section to its file. -->

### 11.2 The SDLC used to build Vyoog

**Branching.** Trunk-based. Short-lived branches, squash-merged to `main` behind a pull
request. No long-lived develop branch. Feature flags for anything that spans sessions.

**Environments.**

| Env | Purpose | Data | Deploy |
|---|---|---|---|
| local | Docker Compose: Postgres, Keycloak, MinIO, Redis | Seed fixture | — |
| ci | Ephemeral, Testcontainers | Per-test | Every push |
| dev | Integration | Synthetic | Auto from `main` |
| staging | Pre-production, real IdP federation | Anonymised | Tagged release |
| production | — | — | Manual approval |

**Definition of Done** — a change is not done until all of these hold:

1. Code merged to `main` behind a green pipeline
2. Unit tests for the logic; **integration test against a real Postgres** via
   Testcontainers for anything touching SQL
3. **A tenant-isolation test** if the change touched a tenant-scoped table
4. Flyway migration is forward-only and tested against a populated database
5. OpenAPI regenerated; frontend types regenerated
6. Audit event emitted for any state change
7. The requirement ID appears in the commit trailer
8. No new `TODO` without a linked issue

**Pipeline.**

```
push → cd backend && ./mvnw -B verify                      compile, unit, integration, ArchUnit
     → ./mvnw -B -pl api spring-boot:build-image
     → OpenAPI diff check
     → npm ci && npm run build && npm run test   (frontend)
     → Playwright smoke
     → deploy dev → migration dry-run against a staging clone
```

`verify` is the single gate: Surefire runs unit tests, Failsafe runs `*IT` integration
tests against Testcontainers, and ArchUnit runs as a plain unit test. One command
reproduces CI locally.

**Testing strategy.**

| Layer | Tool | What it covers |
|---|---|---|
| Unit | JUnit 5 | State machine, detector logic, scoring, lint rules |
| Integration | Testcontainers + REST Assured | Every endpoint against real Postgres and Keycloak |
| **Tenant isolation** | Testcontainers | Query without tenant context returns zero rows. Non-negotiable. |
| Graph | Testcontainers | Recursive CTE termination on cyclic graphs; closure-table consistency |
| Contract | OpenAPI diff | Breaking change fails the build |
| Frontend unit | Vitest + Testing Library | Grid behaviours, lint engine, layout engine |
| E2E | Playwright | Author → review → approve → verify, end to end |
| Load | k6 | Grid at 50k rows; detection sweep at 100k requirements |

**Migrations.** Forward-only. Expand/contract for anything breaking: add the new column,
backfill, dual-write, switch reads, drop the old column in a later release. Never a
destructive migration in the same deploy as the code that stops using the column.

### 11.3 Working with an AI coding agent

The estimate stands: **≈540 requirements across ≈63 capabilities, 40–60 Claude Code
sessions.** For a five-module v1, 18–24 sessions.

**Two-stage rule.** Generate the full requirement *register* up front — one line each,
all 540. Generate requirement *text* just-in-time, per capability, in the session that
implements it. Generating 540 full requirements up front produces a document nobody reads
and an agent that loses the thread.

**Completeness mechanism** — the thing that prevents silent drift:

1. `BUILD-REGISTER.md` at the repository root: every requirement ID, one line, with a
   status column.
2. Every commit carries a trailer: `Requirement: VYB-0142`.
3. Every test is named `VYB0142_AC2_shortDescription`.
4. Every session ends with an audit block appended to the register:
   **Completed / Not done / Discovered**.
5. A CI check fails the build if a commit has no requirement trailer.

This closes the loop: the tool being built enforces on its own construction exactly the
discipline it sells.

---
