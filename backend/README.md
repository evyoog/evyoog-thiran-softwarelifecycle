# Vyoog backend

Spring Boot 3.3 multi-module Maven build. Start with the [root README](../README.md): how to run it,
the required environment variables, the layout and where the plan lives. This file has the
backend-only detail.

## Modules

| Module | What is in it |
|---|---|
| `vyoog-domain` | entities, repositories, services, detectors, and the Flyway migrations (`src/main/resources/db/migration`), which are the database schema |
| `vyoog-api` | REST controllers, security (`SecurityConfig`, `PrincipalGuard`), startup checks, the Boot application |
| `vyoog-worker` | reserved; empty today |
| `vyoog-testkit` | `PostgresFixture` (Testcontainers) and `LocalDatabase`, the guard that keeps integration runners on a local database |

`./mvnw -B verify` builds all four and runs the unit tests and the ArchUnit boundary tests.
Modules may not import each other's `internal` packages (see `CLAUDE.md`).

## Other docs

- `docs/DECISIONS.md`: decision log
- `docs/vyoog-build-specification.md`: specification
- `docs/running-minio-locally.md`: object storage for attachments
- `docs/backup-restore-rehearsal.md`, `docs/load-test-rehearsal.md`: rehearsals
- `docs/vyoog-getting-started.md`, `docs/vyoog-maven-project-setup.md`, `docs/vyoog-claude-code-kickoff.md`:
  the original Phase 0 bootstrap notes, kept for history (see the banners at their tops)

## Running the integration runners locally

The `*VerificationRunner` / `LoadRehearsalRunner` classes in `vyoog-api/src/test` boot the whole
application against a real Postgres. They are invisible to `mvn test` / `mvn verify` (and to CI) on
purpose — run one by name. They only ever use a **local** database:

- **`DB_URL` unset** — a throwaway Testcontainers Postgres (pgvector image) is started for the run and
  discarded afterwards. Needs Docker.
- **`DB_URL` set** — it must point at localhost or a loopback address, normally the docker-compose
  database. Any other host (RDS, a shared server) is refused before anything connects, with a message
  naming the host.

```bash
# Option A: throwaway container (Docker running, DB_URL not set)
cd backend && ./mvnw -B -pl vyoog-api -am test -Dtest=Session14VerificationRunner \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false

# Option B: the docker-compose database
cd backend && docker compose up -d
set -a && source .env && set +a          # DB_URL must be jdbc:postgresql://localhost:...
./mvnw -B -pl vyoog-api -am test -Dtest=Session14VerificationRunner \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

The three Keycloak/SSO secrets the application requires get fake values in a runner unless you have
set them (`AuthEndpointVerificationRunner` needs its own, see its class comment). Runners that talk to
object storage need MinIO from `docker compose up -d`. A runner cleans up the rows it creates, and none
of them touches the seeded `integration_connection` rows: `BriefPushVerificationRunner` swaps in an
in-memory integration service with its own "planning" connection. A new `*Runner` class must extend
`VerificationRunnerBase`; a unit test fails the build otherwise.

## CI

`.github/workflows/ci.yml` runs on every pull request: `cd backend && ./mvnw -B -ntp verify` and, in
`frontend`, `npm ci`, `npx tsc -b` and `npm test`. It needs no secrets or services. Run the same commands
locally before you push.
