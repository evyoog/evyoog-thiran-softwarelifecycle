# Vyoog

Requirements management platform for the SW Life Cycle Application (Thiran SWLC). Requirements
are authored, reviewed, approved and baselined with revisions, and traced to design, code, tests,
deployments, defects and releases. Spring Boot (Java 21) · React 18 + TypeScript + Vite ·
PostgreSQL 16 (pgvector) · Keycloak 25.

Vyoog is **single-tenant by design** (`docs/DECISIONS.md` D3). It lives in its own Postgres
schema (`vyg_requirement`) on the company's shared database instance and signs users in through the
shared `eVyoog` Keycloak realm, the same as vyg-pms and the pricing tool. There is no local Keycloak.

## Where things are

The repository is the single source of truth: requirements, rules, workflows, code, database changes, tests and deployment files live together, so one feature branch can change all of them.

| | |
|---|---|
| **The requirements** | [`docs/02-requirements/`](docs/02-requirements/README.md): every requirement by feature, with status and its automated tests; [planned Phase 6](docs/02-requirements/functional-requirements/phase-6-planned.md); the [build specification by section](docs/02-requirements/SPECIFICATION-INDEX.md). All of `docs/` is indexed in [`docs/README.md`](docs/README.md). |
| **The plan and status** | [`BUILD-REGISTER.md`](BUILD-REGISTER.md): the source of every requirement, one line each, with session logs below the table. Phases 0 to 5 are built (a few rows are `PARTIAL`); **Phase 6 (VYB-0900 onwards) is the current plan**. |
| **Rules for coding agents** | [`CLAUDE.md`](CLAUDE.md): the non-negotiable rules, the Definition of Done, where each kind of thing lives, how sprint sessions work. |
| **Decisions** | [`docs/DECISIONS.md`](docs/DECISIONS.md). D22 is accepted (2026-10-03); D23 to D27 are proposed or open. |
| **Database schema** | [`database/migrations/`](database/migrations): Flyway migrations, forward-only. |
| **Test cases** | Automated: next to the code, indexed by requirement in [`test-cases/automated-tests-index.md`](test-cases/automated-tests-index.md). Manual and UAT: [`test-cases/`](test-cases/README.md). |
| **Deployment** | [`deployment/`](deployment/README.md) (Dockerfiles, nginx, environments); local services in [`docker-compose.yml`](docker-compose.yml). |
| **Secrets** | [`docs/SECRETS-ROTATION.md`](docs/SECRETS-ROTATION.md). |
| **Backend / frontend details** | [`backend/README.md`](backend/README.md), [`frontend/README.md`](frontend/README.md). |

## Run it locally

You need Docker, JDK 21 and Node 20+. You do not need Maven installed (`./mvnw`).

```bash
# 1. Configuration. .env is gitignored; the values in .env.example are fake placeholders.
cp .env.example .env            # then edit: see "Required configuration" below

# 2. Local Postgres (pgvector), Redis and MinIO. docker compose reads DB_PASSWORD from .env.
docker compose up -d            # from the repository root
docker compose ps               # wait until healthy

# 3. The API. Flyway creates the schema on first start. run-local.sh loads .env and refuses a
#    DB_URL that is not localhost.
scripts/run-local.sh            # http://localhost:8080

# 4. The web app, in a second terminal
cd frontend
npm ci
npm run dev                     # http://localhost:5175, proxies /api to VITE_API_PROXY_TARGET
```

The dev server proxies `/api` to `VITE_API_PROXY_TARGET` (default `http://localhost:8081`; the committed
`frontend/.env.local` sets `http://localhost:8083`). Make it match the API's `SERVER_PORT` (default
8080), for example `VITE_API_PROXY_TARGET=http://localhost:8080` in `frontend/.env.local`.

Sign-in uses the shared Keycloak realm. The web app uses the public PKCE client `vyoog-web`; the API's
username and password screen uses the confidential client `vyg-devops-ui`, whose secret only a Keycloak
administrator has. Without it everything except that screen still runs. Details of the clients:
`docs/archive/vyoog-getting-started.md`.

| Service | URL |
|---|---|
| Web (dev server) | http://localhost:5175 |
| API | http://localhost:8080 |
| API health | http://localhost:8080/actuator/health |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| MinIO console | http://localhost:9001 (local credentials in `docker-compose.yml`) |

## Required configuration

No real credential is committed anywhere (D22). These six variables have **no default**; the API stops
at startup and names every one that is empty. Locally they live in `.env` at the repository root; deployed, they come
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
| `JWT_AUDIENCE` | empty (not enforced) | required `aud` value, normally `vyoog-api`; set it once the Keycloak audience mapper exists ([`audience.md`](docs/08-architecture/security/audience.md)) |
| `IDEMPOTENCY_RETENTION_DAYS`, `WEBHOOK_DELIVERY_RETENTION_DAYS` | `7`, `90` | how long idempotency keys and webhook delivery ids are kept before the nightly purge; the webhook value is also the replay-protection window. Minimum 1 |

The full list, with comments, is `.env.example`.

## Test and build

```bash
cd backend  && ./mvnw -B verify                          # compile, unit tests, ArchUnit
cd frontend && npm run lint && npx tsc -b && npm test   # lint, type check and unit tests
python3 scripts/generate-requirements-docs.py --check    # docs/02-requirements matches the register
```

The same three commands run in CI on every pull request (`.github/workflows/ci.yml`); CI needs no secrets or
services except Docker, which the integration tests (`*IT`) use to start a throwaway PostgreSQL
(or set `DB_URL` to a local database; see [`docs/08-architecture/testing.md`](docs/08-architecture/testing.md)). The manual
`*VerificationRunner` classes that boot the full application against a real Postgres, and how to run
them safely against a local database only, are in `backend/README.md`.

## Layout

```
frontend/            React + TypeScript + Vite single-page app
backend/             Spring Boot multi-module build
  vyoog-domain/        entities, repositories, services, detectors
  vyoog-api/           controllers, security, the Boot application
  vyoog-worker/        reserved (empty)
  vyoog-testkit/       Testcontainers fixture, local-database guard for runners
ai-service/          reserved for a separate AI service (none today; AI runs in the backend)
docs/                product documentation: requirements, rules, workflows, UI, API, database, architecture, integrations
test-cases/          manual and UAT cases; index of the automated tests by requirement
database/            migrations (the schema), local init, seed/views/functions/procedures
deployment/          Dockerfiles, nginx, aws/ecs, per-environment notes
scripts/             run-local.sh, generate-requirements-docs.py, approve-requirements.sh
.devcontainer/       Codespaces / dev container
.github/             CI workflow, issue and pull request templates
BUILD-REGISTER.md    the requirement register and its history
CLAUDE.md            working rules for coding agents
docker-compose.yml   local Postgres, Redis, MinIO
.env.example         every environment variable, fake values
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
