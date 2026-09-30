> **Superseded by `docs/DECISIONS.md` D3 and D6 (session 2).** This runbook describes
> the original shared-schema+RLS tenancy model and a Vyoog-local Keycloak realm. Neither
> is what this repository does anymore: Vyoog is single-tenant, runs in its own
> `vyg_requirement` Postgres schema on the shared company RDS instance, and
> authenticates against the shared `eVyoog` Keycloak realm — see those two decisions for
> why. Steps 3–6 and the Step 8 checklist below describe the *old* model and are kept
> only as a historical record of how Phase 0 was originally bootstrapped. For what
> actually needs setting up now, see **"Shared infrastructure setup (current)"** just
> below this banner.
>
> ## Shared infrastructure setup (current)
>
> There is no local Postgres role split and no local Keycloak realm to import. What's
> actually needed:
>
> 1. **Database.** The schema already exists: `CREATE SCHEMA vyg_requirement
>    AUTHORIZATION postgres` on the shared RDS instance (same instance vyg-pms and the
>    pricing tool use). `application.yml` has no defaults for
>    `DB_URL`/`DB_USER`/`DB_PASSWORD`: the app will not start until they are set
>    (`docs/DECISIONS.md` D22 restores this doc's original "never commit the real
>    values" guidance and supersedes D9). Local dev uses docker-compose's Postgres via
>    `.env`; deployed environments take them from the secrets manager.
> 2. **Keycloak — frontend client.** Ask whoever administers `https://user.evyoog.com`
>    to create a client in the **eVyoog** realm:
>    ```
>    Client ID:        vyoog-web
>    Client type:      Public (no secret)
>    Standard flow:    Enabled (Authorization Code)
>    Direct access grants: Disabled   — do NOT copy vyg-pms's password-grant pattern;
>                                        see docs/DECISIONS.md for why that one's a problem
>    PKCE:             Required, S256
>    Valid redirect URIs:        https://requirements.evyoog.com/*, http://localhost:5173/*
>    Valid post-logout redirects: https://requirements.evyoog.com/*, http://localhost:5173/*
>    Web origins:                https://requirements.evyoog.com, http://localhost:5173
>    ```
>    Not registered yet as of session 4 — sign-in will not complete until it is.
> 3. **Keycloak — API audience (optional, do later).** The resource server currently
>    validates issuer and signature only (no `aud` check — see D6). To tighten that,
>    ask for a `vyoog-api` client (bearer-only) and an audience mapper on `vyoog-web`
>    that includes it, then add `audiences: vyoog-api` back to `application.yml`.
> 4. **Local dev loop.** `docker compose up -d` starts local Postgres/Redis/MinIO only.
>    Sign-in always goes against the real shared Keycloak — there is no offline
>    substitute for that part.

# Vyoog — how to start

A Day 1 runbook. Everything here is copy-and-run. At the end you will have a repository,
a running Postgres with the correct roles, a configured Keycloak, and a first Claude Code
session with a clear exit criterion.

Budget about **three hours** for the whole thing, most of it waiting for downloads.

---

## Before you type anything

### Two decisions that block Phase 0

Everything else can be decided later. These two cannot, because changing them after code
exists is expensive.

**1. Can one person belong to more than one tenant?**

| | If **no** (recommended default) | If **yes** |
|---|---|---|
| Token | Single `vyoog_tenant` claim | `vyoog_tenants` array claim |
| Tenant source | Read from the JWT, always | An `X-Vyoog-Tenant` header, **validated as a member of the array** |
| Complexity | Low | Every request needs the membership check; tenant switching in the UI |

Choose **no** unless you already know consultants or auditors need cross-tenant access.
Going from no → yes later is a contained change to the security layer. Going yes → no is
trivial. Building yes when you needed no is wasted work.

**2. Requirement key scheme.** `VY-1042` (sequential per tenant) or `VY-ATT-0042`
(capability-encoded)? Sequential is simpler and is the default in the schema.
Capability-encoded reads better in commit messages but breaks when a requirement moves
capability. **Changing this after data exists means rewriting every key and every commit
trailer that references one.**

Record both answers in `docs/DECISIONS.md` on day one.

### One risk worth naming

The specification recommends shared-schema tenancy with Postgres RLS. **If `vyg-pms`
already uses Hibernate `SCHEMA` or `DATABASE` multitenancy, that recommendation is
wrong for you** and Phase 0 would build the wrong foundation.

Two options:

- **Safer.** Share the four files from §3.7 of the specification first
  (`CurrentTenantIdentifierResolver*`, `MultiTenantConnectionProvider*`,
  `application*.yml`, `V1__*.sql`). One review, then start.
- **Faster.** Start now and accept that the tenancy layer may be rewritten. It is
  genuinely contained — roughly one session — because everything else reads tenancy
  through `TenantContext`.

Either is defensible. Starting blind and *not* knowing you might rewrite is not.

---

## Step 1 — Install the toolchain

```bash
# Java 21 via SDKMAN
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 21.0.4-tem
java -version          # expect 21.x

# Node 20 via nvm (for the frontend)
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash
nvm install 20 && node -v

# Docker Desktop — install from docker.com, then:
docker --version && docker compose version

# Claude Code
npm install -g @anthropic-ai/claude-code
```

You do **not** need Maven installed. The wrapper (`mvnw`) is committed and downloads
itself.

---

## Step 2 — Create the repository skeleton

Save this as `bootstrap.sh`, run it once.

```bash
#!/usr/bin/env bash
set -euo pipefail

mkdir -p vyoog && cd vyoog
git init -b main

mkdir -p docs \
         infra/db/init \
         infra/keycloak \
         backend/vyoog-domain/src/main/java/com/vyoog \
         backend/vyoog-domain/src/main/resources/db/migration \
         backend/vyoog-api/src/main/java/com/vyoog/api \
         backend/vyoog-api/src/main/resources \
         backend/vyoog-worker/src/main/java/com/vyoog/worker \
         backend/vyoog-testkit/src/main/java/com/vyoog/testkit \
         frontend/src

# Copy the four planning documents in — Claude Code reads these.
# cp /path/to/vyoog-build-specification.md   docs/
# cp /path/to/vyoog-schema.sql               docs/
# cp /path/to/vyoog-maven-project-setup.md   docs/
# cp /path/to/vyoog-claude-code-kickoff.md   docs/

# The schema becomes the Flyway baseline.
# cp docs/vyoog-schema.sql backend/vyoog-domain/src/main/resources/db/migration/V001__baseline.sql

cat > .gitignore <<'EOF'
target/
node_modules/
.env
*.log
.DS_Store
.idea/
.vscode/
EOF

cat > docs/DECISIONS.md <<'EOF'
# Decision log

| # | Decision | Choice | Date | Rationale |
|---|---|---|---|---|
| D1 | Multi-tenant users | no | | Single vyoog_tenant claim |
| D2 | Requirement key scheme | VY-nnnn | | Sequential per tenant |
| D3 | Tenancy model | shared schema + RLS | | Pending vyg-pms review |
EOF

git add -A && git commit -m "chore: repository skeleton

Requirement: VYB-0000"
echo "Done. Next: create infra/ files, then run docker compose up -d"
```

Then paste the POMs from `vyoog-maven-project-setup.md` into place and generate the
wrapper once:

```bash
cd backend && mvn -N wrapper:wrapper -Dmaven=3.9.9 && cd ..   # only time you need system Maven
git add mvnw mvnw.cmd .mvn && git commit -m "chore: maven wrapper"
```

---

## Step 3 — Database roles

**This is the step most projects get wrong.** RLS does not apply to the table owner, so
if the application connects as the owner, every isolation policy silently does nothing.

`infra/db/init/01-roles.sql` — Postgres runs everything in this directory on first start:

```sql
-- Owns the schema, runs Flyway, bypasses RLS by design.
CREATE ROLE vyoog_migrator LOGIN PASSWORD 'migrator_dev_pw';

-- The application. Not the owner. Subject to RLS. This is the whole point.
CREATE ROLE vyoog_app LOGIN PASSWORD 'app_dev_pw';

-- Reads the outbox across tenants, and nothing else.
CREATE ROLE vyoog_relay LOGIN PASSWORD 'relay_dev_pw';

GRANT ALL   ON DATABASE vyoog TO vyoog_migrator;
GRANT USAGE ON SCHEMA public  TO vyoog_app, vyoog_relay;

-- Applies to tables Flyway has not created yet.
ALTER DEFAULT PRIVILEGES FOR ROLE vyoog_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO vyoog_app;
ALTER DEFAULT PRIVILEGES FOR ROLE vyoog_migrator IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO vyoog_app;
```

---

## Step 4 — `docker-compose.yml`

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg16
    container_name: vyoog-db
    environment:
      POSTGRES_DB: vyoog
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports: ["5432:5432"]
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./infra/db/init:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d vyoog"]
      interval: 5s
      timeout: 5s
      retries: 10

  keycloak-db:
    image: postgres:16
    container_name: vyoog-kc-db
    environment:
      POSTGRES_DB: keycloak
      POSTGRES_USER: keycloak
      POSTGRES_PASSWORD: keycloak
    volumes: [kcdata:/var/lib/postgresql/data]

  keycloak:
    image: quay.io/keycloak/keycloak:25.0
    container_name: vyoog-keycloak
    command: start-dev --import-realm
    environment:
      KC_BOOTSTRAP_ADMIN_USERNAME: admin
      KC_BOOTSTRAP_ADMIN_PASSWORD: admin
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://keycloak-db:5432/keycloak
      KC_DB_USERNAME: keycloak
      KC_DB_PASSWORD: keycloak
      KC_HEALTH_ENABLED: "true"
    ports: ["8081:8080"]
    volumes:
      - ./infra/keycloak:/opt/keycloak/data/import:ro
    depends_on: [keycloak-db]

  redis:
    image: redis:7-alpine
    container_name: vyoog-redis
    ports: ["6379:6379"]

  minio:
    image: minio/minio
    container_name: vyoog-minio
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: minio
      MINIO_ROOT_PASSWORD: minio123
    ports: ["9000:9000", "9001:9001"]
    volumes: [miniodata:/data]

volumes:
  pgdata:
  kcdata:
  miniodata:
```

Keycloak is on **8081** so the API can have 8080.

---

## Step 5 — Keycloak realm

`infra/keycloak/vyoog-realm.json`. Imported automatically on first start.

```json
{
  "realm": "vyoog",
  "enabled": true,
  "sslRequired": "none",
  "registrationAllowed": false,
  "loginWithEmailAllowed": true,
  "accessTokenLifespan": 900,
  "ssoSessionIdleTimeout": 28800,
  "roles": {
    "realm": [
      { "name": "platform-admin" },
      { "name": "tenant-admin" },
      { "name": "user" },
      { "name": "service-account" }
    ]
  },
  "clientScopes": [
    {
      "name": "vyoog-tenant",
      "protocol": "openid-connect",
      "attributes": { "include.in.token.scope": "true" },
      "protocolMappers": [
        {
          "name": "tenant-claim",
          "protocol": "openid-connect",
          "protocolMapper": "oidc-usermodel-attribute-mapper",
          "config": {
            "user.attribute": "tenant_id",
            "claim.name": "vyoog_tenant",
            "jsonType.label": "String",
            "access.token.claim": "true",
            "id.token.claim": "true",
            "userinfo.token.claim": "true"
          }
        },
        {
          "name": "vyoog-audience",
          "protocol": "openid-connect",
          "protocolMapper": "oidc-audience-mapper",
          "config": {
            "included.client.audience": "vyoog-api",
            "access.token.claim": "true"
          }
        }
      ]
    }
  ],
  "clients": [
    {
      "clientId": "vyoog-web",
      "name": "Vyoog web",
      "enabled": true,
      "publicClient": true,
      "standardFlowEnabled": true,
      "directAccessGrantsEnabled": false,
      "redirectUris": ["http://localhost:5173/*"],
      "webOrigins": ["http://localhost:5173"],
      "attributes": {
        "pkce.code.challenge.method": "S256",
        "post.logout.redirect.uris": "http://localhost:5173/*"
      },
      "defaultClientScopes": [
        "openid", "profile", "email", "roles", "vyoog-tenant"
      ]
    },
    {
      "clientId": "vyoog-api",
      "name": "Vyoog API",
      "enabled": true,
      "bearerOnly": true,
      "publicClient": false
    },
    {
      "clientId": "vyoog-service",
      "name": "Vyoog integrations",
      "enabled": true,
      "publicClient": false,
      "serviceAccountsEnabled": true,
      "standardFlowEnabled": false,
      "secret": "service_dev_secret",
      "defaultClientScopes": ["openid", "roles", "vyoog-tenant"]
    }
  ],
  "users": [
    {
      "username": "founder",
      "email": "founder@acme.test",
      "firstName": "Test",
      "lastName": "Founder",
      "enabled": true,
      "emailVerified": true,
      "attributes": {
        "tenant_id": ["11111111-1111-1111-1111-111111111111"]
      },
      "credentials": [
        { "type": "password", "value": "founder", "temporary": false }
      ],
      "realmRoles": ["user", "tenant-admin"]
    }
  ]
}
```

The user's `tenant_id` attribute must match a row in the `tenant` table. Seed it:

```sql
INSERT INTO tenant (id, slug, name, req_key_prefix)
VALUES ('11111111-1111-1111-1111-111111111111', 'acme', 'Acme Corp', 'VY');
```

Put that in a **dev-only** Flyway callback or a seed script — never in a versioned
migration, or it ships to production.

---

## Step 6 — Bring it up and verify

```bash
docker compose up -d
docker compose ps                 # all healthy

# Postgres roles exist?
docker exec -it vyoog-db psql -U postgres -d vyoog -c "\du"
# expect vyoog_app, vyoog_migrator, vyoog_relay

# pgvector available?
docker exec -it vyoog-db psql -U postgres -d vyoog \
  -c "CREATE EXTENSION IF NOT EXISTS vector; SELECT extversion FROM pg_extension WHERE extname='vector';"

# Keycloak realm imported?
curl -s http://localhost:8081/realms/vyoog/.well-known/openid-configuration | head -c 200

# Can the test user get a token?
curl -s -X POST \
  http://localhost:8081/realms/vyoog/protocol/openid-connect/token \
  -d client_id=vyoog-web -d grant_type=password \
  -d username=founder -d password=founder | python3 -m json.tool
```

The last one only works if you temporarily enable direct access grants — useful for a
smoke test, then turn it off. Decode the token at jwt.io and confirm **`vyoog_tenant` is
present** and **`aud` contains `vyoog-api`**. If either is missing, fix the realm before
writing a line of Java.

---

## Step 7 — First Claude Code session

```bash
cd vyoog
claude
```

Then paste the **kickoff prompt** from `docs/vyoog-claude-code-kickoff.md` §2.

Three things to hold the agent to:

1. **It must present a plan and its questions before coding.** The prompt says so. If it
   starts writing files immediately, stop it and ask for the plan.
2. **Phase 0 only.** If you see a `RequirementController` appear, it has drifted. Stop it.
3. **The exit criterion is a passing test**, not a screenshot.

Expect Phase 0 to take two to four sessions, not one. That is normal.

---

## Step 8 — How you know Phase 0 is actually done

Not "it looks right". These, specifically:

```bash
cd backend && ./mvnw -B verify
```

- [ ] `TenantIsolationIT` passes — **an unbound query returns zero rows, not all rows**
- [ ] A second test proves tenant A cannot see tenant B's rows
- [ ] A third proves inserting into another tenant is refused by `WITH CHECK`
- [ ] ArchUnit passes — no module imports another module's `internal` package
- [ ] Flyway applied `V001__baseline.sql` as `vyoog_migrator`, and the app connects as
      `vyoog_app`
- [ ] `curl localhost:8080/actuator/health` returns `UP`
- [ ] Logging in at `localhost:5173` redirects to Keycloak and back with a token
- [ ] An `app_user` row was created automatically on first login
- [ ] The sidebar renders ten modules with a divider before Administration
- [ ] Dark and light themes both work
- [ ] `BUILD-REGISTER.md` has a session audit block

**Do not start Phase 1 until every box is ticked.** Phase 1 builds the register on top of
tenancy; discovering the foundation is wrong at session 12 costs far more than fixing it
at session 3.

---

## The first week, realistically

| Day | Work |
|---|---|
| 1 | Steps 1–6. Infrastructure up and verified. No application code. |
| 2 | Session 1. Maven skeleton, ArchUnit, Flyway baseline. |
| 3 | Session 2. Tenancy layer and the three isolation tests. **The most important day.** |
| 4 | Session 3. Keycloak resource server, `app_user` upsert, step-up scaffolding. |
| 5 | Session 4. React shell, design tokens, OIDC login, empty Home. |

End of week one: you can log in, the shell renders, and isolation is proven. That is a
genuinely good first week for a system this size — resist the urge to have a requirements
grid by Friday.

---

## Common first-week failures

| Symptom | Cause |
|---|---|
| Isolation test passes but production leaks | The app connects as the table owner. Check `\du` — the app must be `vyoog_app`, and policies need `FORCE ROW LEVEL SECURITY`. |
| Tenant context leaks between requests | `SET` was used instead of `SET LOCAL`, so it survives on the pooled connection. |
| Tenant is null on some requests | Context bound in a servlet filter rather than at transaction start. Async and scheduled work has no filter. |
| `401` with a valid-looking token | `aud` does not contain `vyoog-api`. Add the audience mapper. |
| Testcontainers tests are slow | They are running under Surefire. Rename to `*IT.java`. |
| Flyway fails on second run | A migration was edited after being applied. Never edit an applied migration; add a new one. |
| pgvector missing | Using `postgres:16` instead of `pgvector/pgvector:pg16`. |

---

## What to send me next

Whichever comes first:

- **The `vyg-pms` files** from §3.7 of the specification, so the tenancy decision is
  settled before it is expensive. This is the higher-value one.
- **The session 1 audit block** from `BUILD-REGISTER.md`, and I will review what the
  agent actually built against the specification.
- **Any point where the agent asks a question the spec does not answer** — that is a gap
  in the spec, and I would rather fix it than have the agent guess.
