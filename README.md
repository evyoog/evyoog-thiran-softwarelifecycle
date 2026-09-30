# Vyoog

Requirements management platform for the SW Life Cycle Application (Thiran SWLC). Requirements
are authored, reviewed, approved and baselined with revisions, and traced to design, code, tests,
deployments, defects and releases. Spring Boot (Java 21) · React 18 + TypeScript + Vite ·
PostgreSQL 16 (pgvector) · Keycloak 25.

Vyoog is **single-tenant by design** (`backend/docs/DECISIONS.md` D3). It lives in its own Postgres
schema (`vyg_requirement`) on the company's shared database instance and signs users in through the
shared `eVyoog` Keycloak realm, the same as vyg-pms and the pricing tool. There is no local Keycloak.

## Where things are

| | |
|---|---|
| **The plan and status** | [`BUILD-REGISTER.md`](BUILD-REGISTER.md): every requirement, one line, with status; session logs below the table. Phases 0 to 5 are built (a few rows are `PARTIAL`, with the gap written in the row's session log). **Phase 6 (VYB-0900 onwards) is the current plan**: Sprint 1 hardens security and the build, later sprints add connectors, manual test execution, AI governance and more. |
| **Rules for coding agents** | [`CLAUDE.md`](CLAUDE.md): the non-negotiable rules, the Definition of Done and how sprint sessions work. |
| **Decisions** | [`backend/docs/DECISIONS.md`](backend/docs/DECISIONS.md). D22 to D27 are proposed or open. |
| **Specification** | [`backend/docs/vyoog-build-specification.md`](backend/docs/vyoog-build-specification.md) |
| **Database schema** | The Flyway migrations in `backend/vyoog-domain/src/main/resources/db/migration` are the schema. |
| **Secrets** | [`docs/SECRETS-ROTATION.md`](docs/SECRETS-ROTATION.md): what to rotate, who owns each, and how to scrub git history. |
| **Backend details** | [`backend/README.md`](backend/README.md): modules, running the integration runners, CI. |
| **Frontend details** | [`frontend/README.md`](frontend/README.md) |

## Run it locally

You need Docker, JDK 21 and Node 20+. You do not need Maven installed (`./mvnw`).

```bash
# 1. Configuration. backend/.env is gitignored; the values in .env.example are fake placeholders.
cd backend
cp .env.example .env            # then edit: see "Required configuration" below

# 2. Local Postgres (pgvector), Redis and MinIO. docker compose reads DB_PASSWORD from .env.
docker compose up -d
docker compose ps               # wait until healthy

# 3. The API. Flyway creates the schema on first start. run-local.sh loads .env and refuses a
#    DB_URL that is not localhost.
./run-local.sh                  # http://localhost:8080

# 4. The web app, in a second terminal
cd ../frontend
npm ci
npm run dev                     # http://localhost:5175, proxies /api to VITE_API_PROXY_TARGET
```

The dev server proxies `/api` to `VITE_API_PROXY_TARGET` (default `http://localhost:8081`; the committed
`frontend/.env.local` sets `http://localhost:8083`). Make it match the API's `SERVER_PORT` (default
8080), for example `VITE_API_PROXY_TARGET=http://localhost:8080` in `frontend/.env.local`.

Sign-in uses the shared Keycloak realm. The web app uses the public PKCE client `vyoog-web`; the API's
username and password screen uses the confidential client `vyg-devops-ui`, whose secret only a Keycloak
administrator has. Without it everything except that screen still runs. Details of the clients:
`backend/docs/vyoog-getting-started.md`.

| Service | URL |
|---|---|
| Web (dev server) | http://localhost:5175 |
| API | http://localhost:8080 |
| API health | http://localhost:8080/actuator/health |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| MinIO console | http://localhost:9001 (local credentials in `backend/docker-compose.yml`) |

## Required configuration

No real credential is committed anywhere (D22). These six variables have **no default**; the API stops
at startup and names every one that is empty. Locally they live in `backend/.env`; deployed, they come
from the secrets manager.

| Variable | What it is |
|---|---|
| `DB_URL` | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/vygmicroservice?currentSchema=vyg_requirement,public` |
| `DB_USER`, `DB_PASSWORD` | database login (`DB_PASSWORD` is also the docker-compose Postgres password) |
| `KEYCLOAK_ROPC_CLIENT_SECRET` | secret of the `vyg-devops-ui` client (username and password sign-in) |
| `KEYCLOAK_IMPERSONATION_CLIENT_SECRET` | secret of the `eVyoog` client (cross-app SSO) |
| `INTERNAL_SSO_SHARED_SECRET` | shared with every other app's backend in the SSO mesh |

Optional:

| Variable | Default | Purpose |
|---|---|---|
| `CORS_ALLOWED_ORIGINS` | `https://devops.evyoog.com` | exact origins allowed to call the API with credentials |
| `CORS_ALLOWED_ORIGIN_PATTERNS` | empty | host patterns for local development, for example `http://localhost:*`. A bare `*` is refused at startup |
| `BOOTSTRAP_TOKEN` | empty (off) | enables the one-time `POST /api/v1/settings/bootstrap` that creates the first administrator; set it only while bootstrapping, then unset it |
| `ATTACHMENT_MAX_BYTES` | `10485760` | largest single attachment upload |
| `AI_ENABLED`, `AI_API_KEY` | off | OpenAI-backed advisory features; each reports itself unconfigured without a key |
| `AUTH_COOKIE_SECURE` | `true` | set `false` only for plain-HTTP development on a non-localhost host |

The full list, with comments, is `backend/.env.example`.

## Test and build

```bash
cd backend  && ./mvnw -B verify           # compile, unit tests, ArchUnit
cd frontend && npx tsc -b && npm test     # type check and unit tests
```

The same two commands run in CI on every pull request (`.github/workflows/ci.yml`); CI needs no secrets or
services. There are no integration tests (`*IT`) yet: Sprint 2 adds them (VYB-0907). The manual
`*VerificationRunner` classes that boot the full application against a real Postgres, and how to run
them safely against a local database only, are in `backend/README.md`.

## Layout

```
backend/
  pom.xml                 parent reactor
  vyoog-domain/           entities, repositories, services, detectors, Flyway migrations
  vyoog-api/              controllers, security, the Spring Boot application
  vyoog-worker/           reserved for the detection sweep and outbox relay (currently empty)
  vyoog-testkit/          Testcontainers fixture and the local-database guard for runners
  docs/                   decisions, specification, runbooks
  docker-compose.yml      local Postgres, Redis, MinIO
  .env.example            every environment variable, fake values
frontend/                 React + TypeScript + Vite single-page app
docs/                     SECRETS-ROTATION.md
.github/workflows/        CI
BUILD-REGISTER.md         the plan and its history
CLAUDE.md                 working rules for coding agents
```

## Rules that are not negotiable

The full list is in [`CLAUDE.md`](CLAUDE.md). In short:

- Single-tenant, schema isolation. No `tenant_id`, no row-level security.
- Keycloak owns authentication. Vyoog never stores a password.
- Verified is not a requirement status. Test evidence is recorded, but a person decides.
- Tasks are derived from requirement state, never authored.
- Amber means AI output, and AI proposes while a human decides.
- No money, no hours, no effort estimates, no per-person productivity.
- Borrowed data looks borrowed; absence reads "not connected", never blank or zero.
- No real credentials in the repo; every write endpoint has an explicit role rule.
