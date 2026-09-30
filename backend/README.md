# Vyoog

Requirements management platform. Spring Boot (Java 21) · React 18 + TypeScript ·
PostgreSQL 16 · Keycloak 25.

**Phases 0–2 are built** (foundation, the requirement register, and flow/evidence —
review rounds, verification, defects, clarifications, derived work, change requests).
See `BUILD-REGISTER.md` for the line-by-line status of every requirement and
`docs/vyoog-build-specification.md` §12 for the phase sequence.

Vyoog is **single-tenant by design** (see `docs/DECISIONS.md` D3): it lives in its own
Postgres schema (`vyg_requirement`) on the same shared RDS instance as the company's
other applications, and authenticates against the same shared Keycloak realm (`eVyoog`)
as vyg-pms and the pricing tool — not a Vyoog-local realm or database.

---

## Run it

You need Docker, JDK 21 and Node 20+. You do **not** need Maven installed.

```bash
# 1. local Postgres, Redis, MinIO (Keycloak is the shared eVyoog realm — no local one).
#    Needs backend/.env first (step 2); docker compose reads DB_PASSWORD from it.
cd backend && cp .env.example .env
docker compose up -d
docker compose ps                       # wait for healthy

# 2. backend — Flyway applies V001__baseline.sql into the vyg_requirement schema
#    on first start. Configuration comes from backend/.env (gitignored); no secret
#    has a default, and the app refuses to start naming any that is missing.
cd backend && cp .env.example .env      # fill in the placeholders; see the table below
docker compose up -d                    # (reads DB_PASSWORD from .env)
./run-local.sh                          # loads .env, insists DB_URL is localhost

# 3. frontend, in a second terminal
cd frontend && cp .env.example .env     # then set VITE_KEYCLOAK_CLIENT_ID, see below
npm install && npm run dev
```

Open `http://localhost:5173`.

### Required configuration (D22)

No real credential is committed anywhere. These six variables have **no default**; the
API stops at startup and names every one that is empty. Local values live in
`backend/.env` (see `.env.example`); deployed values come from the secrets manager.

| Variable | What it is |
|---|---|
| `DB_URL` | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/vygmicroservice?currentSchema=vyg_requirement,public` |
| `DB_USER`, `DB_PASSWORD` | database login (`DB_PASSWORD` is also the docker-compose Postgres password) |
| `KEYCLOAK_ROPC_CLIENT_SECRET` | secret of the `vyg-devops-ui` client (username/password sign-in) |
| `KEYCLOAK_IMPERSONATION_CLIENT_SECRET` | secret of the `eVyoog` client (cross-app SSO) |
| `INTERNAL_SSO_SHARED_SECRET` | shared with every other app's backend in the SSO mesh |

CORS is an explicit allowlist: `CORS_ALLOWED_ORIGINS` (exact, default
`https://devops.evyoog.com`) and `CORS_ALLOWED_ORIGIN_PATTERNS` (local-dev hosts, empty by
default; `.env.example` sets localhost). A bare `*` is refused at startup. For rotating the
secrets and scrubbing git history, see `../docs/SECRETS-ROTATION.md`.

**Optional, deliberately off by default:** `BOOTSTRAP_TOKEN`. `POST /api/v1/settings/bootstrap`
(the one-time "make this person the first administrator" call) is refused unless this is set,
the request carries it as an `X-Bootstrap-Token` header, the caller is a person, no
administrator exists and the deployment has not been bootstrapped. Set it only while
bootstrapping a new deployment, then unset it. `ATTACHMENT_MAX_BYTES` (default 10 MiB) caps a
single upload; attachments are limited to a fixed list of document, image and text types.

CI ingestion (`/api/v1/ci/*`) needs a **registered** service account that holds the `ci:ingest`
scope. A token is no longer treated as a service account just because it has no email claim.

The `*VerificationRunner` classes start the full application, so they need the same six
variables and must only be pointed at a local database.

**Before sign-in works**, a Keycloak admin needs to register a client in the real
`eVyoog` realm — see `docs/vyoog-getting-started.md` for exactly what to create
(a public, PKCE-enabled client for the frontend; optionally an audience mapper for the
API). There is no local Keycloak to fall back on.

| Service | URL |
|---|---|
| Web | http://localhost:5173 |
| API | http://localhost:8080 |
| API health | http://localhost:8080/actuator/health |
| Swagger | http://localhost:8080/swagger-ui.html |
| Keycloak (shared, not local) | https://user.evyoog.com |
| MinIO console | http://localhost:9001 (minio / minio123) |

## Verify Phase 0

```bash
cd backend && ./mvnw -B verify
```

`FoundationSmokeIT` proves the app starts against the real schema shape (Flyway ran
into `vyg_requirement`) and a product round-trips through the repository layer.
`ArchitectureTest` proves the domain module has no dependency on web types.

**Do not start Phase 1 until both are green.**

---

## Layout

```
backend/
  pom.xml               parent reactor
  vyoog-domain/         entities, repositories, detectors
  vyoog-api/            controllers, security, the Boot application
  vyoog-worker/         detection sweep, outbox relay (Phase 2+)
  vyoog-testkit/        Testcontainers fixture, schema vyg_requirement
frontend/               React + TypeScript + Vite
infra/                  docker-compose support: local db init
docs/                   specification, schema, decisions, build sequence, runbook
docker-compose.yml      local postgres, redis, minio
CLAUDE.md               rules the coding agent reads automatically
BUILD-REGISTER.md       every requirement, one line, with status
```

## How isolation works

Vyoog does not implement multi-tenancy itself. Isolation between Vyoog and the
company's other applications is a Postgres schema boundary, enforced by which schema
the connection targets (`vyg_requirement`, owned by `postgres`) — the same convention
vyg-pms and the pricing tool use for their own schemas. See `docs/DECISIONS.md` D3 for
the full rationale, and why the original RLS/`tenant_id` design was dropped.

Identity comes from the shared `eVyoog` Keycloak realm (`docs/DECISIONS.md` D6):
Spring's OAuth2 resource server validates issuer and signature against that realm's
JWKS; `UserProvisioningService` mirrors the subject into `app_user` on first sight.

## Rules that are not negotiable

Full list in `docs/vyoog-build-specification.md` §1.4 and `CLAUDE.md`.

- Vyoog never stores a password. Keycloak owns authentication.
- "Verified" is a predicate over the current revision, never a stored flag.
- Tasks are derived from requirement state, never authored.
- Amber means AI output, and only AI output.
- No money, no hours, no effort estimates, no per-person productivity.
- Borrowed data looks borrowed; absence reads "not connected", never blank.

## Next

Phase 1 is the requirement register: CRUD with revisions, the state machine, the data
grid, the create screen with live linting, trace links, and the six graph detectors.
Use the per-session prompt in `docs/vyoog-claude-code-kickoff.md` §3.
