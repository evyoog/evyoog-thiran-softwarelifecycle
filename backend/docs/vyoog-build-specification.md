# Vyoog — Complete Build Specification

**Stack:** Spring Boot (Java 21) · React 18 + TypeScript · PostgreSQL 16 · Keycloak 25
**Status of this document:** authoritative build source. Where this document and the
React prototype (`VyoogApp.jsx`) disagree, this document wins — the prototype validated
the interaction model, not the system.

---

## 0. How to use this document

This is the master specification. It is written to be handed to an AI coding agent
(Claude Code) or to a human team, section by section. It is deliberately long because
the instruction was *do not miss any feature*.

Three companion files ship with it:

| File | Purpose |
|---|---|
| `vyoog-build-specification.md` | This document. Architecture, data model, every feature. |
| `vyoog-schema.sql` | Runnable PostgreSQL DDL for the whole model. |
| `vyoog-claude-code-kickoff.md` | Paste-ready prompts: project kickoff and the per-session protocol. |

**Do not attempt to build this in one pass.** Section 12 breaks the work into phases and
sessions. The single most common failure mode for a system this size is an agent that
generates 60% of everything and finishes nothing.

### Reading order for the build team

1. §1 Product definition — what this is and what it refuses to be
2. §2 Architecture, §3 Multi-tenancy, §4 Keycloak — decide these before writing code
3. §5 Data model — the shape everything else assumes
4. §6 Gap detection — the differentiator
5. §7 Feature inventory — the work itself
6. §11 SDLC — how work flows, both in the product and in building it
7. §12 Build sequence — what to do first

---

## 1. Product definition

### 1.1 What Vyoog is

A requirements management platform that treats the **requirement graph** as the primary
asset and continuously detects where that graph is broken. It combines the register
discipline of DOORS/Polarion/Jama with continuous cross-application gap detection, and
it generates implementation briefs that a developer or an AI coding agent can execute.

### 1.2 Hierarchy

```
Platform → Product → App → Capability → Requirement
```

Every requirement belongs to exactly one capability. Every capability belongs to exactly
one app. This is rigid on purpose — it is what makes cross-app duplicate detection and
scope arithmetic possible.

### 1.3 The lifecycle Vyoog manages

```
Author → Review → Approve → Develop → Test → Deploy
```

Full accountability at every stage: who created, who reviewed, who approved, who
developed, who tested, who deployed. Recorded as events, never as assignments.

### 1.4 Non-negotiable product principles

These are load-bearing. Violating them produces a different, worse product.

| # | Principle | Consequence for implementation |
|---|---|---|
| 1 | **Twelve gap classes.** The first four need no AI — they are pure graph queries. | Ship v1 with GRAPH + RULE detectors only. AI is Phase 4. |
| 2 | **Gap visibility is ambient, not a destination.** | Coverage pips `U D C T` on every grid row; gaps surface in place, not only in Analytics. |
| 3 | **Amber means AI, and only AI.** Never a status colour. | Status uses green/red/blue/grey. Anything AI-derived is amber. Enforce in the design tokens. |
| 4 | **Borrowed data looks borrowed.** | Anything Vyoog displays but does not own renders hatched/dashed with a source tag. Absence renders as "not connected", never as blank or zero. |
| 5 | **VERIFIED is not a requirement status (D16).** | APPROVED is the terminal status of the requirement lifecycle. Test evidence (a linked test's pass/fail against the *current* revision) is still tracked, strictly, and any revision bump still invalidates it automatically — but it is a quality-reporting predicate (`requirement_verification_state.is_verified`), never a value `requirement.status` can hold. "Verifying" is a person's action on an IN_REVIEW requirement (→ REVIEWED), not something CI reports back automatically. |
| 6 | **Tasks are derived, never authored.** | There is no "create task" endpoint. Tasks are a query over requirement state. |
| 7 | **AI proposes, the human decides.** | No AI output is ever applied automatically. Every suggestion has an explicit accept/dismiss, and dismissals are stored with a reason. |
| 8 | **Vyoog never stores a password.** | Keycloak owns authentication. There is no password column, no password reset flow, no field to set one. |
| 9 | **Access is granted as role × scope.** | Not "Reviewer" but "Reviewer on Valam ▸ HR Intelligence". See §4.4. |
| 10 | **Vyoog holds no money and no hours.** | No cost tables, no timesheets, no per-person productivity metrics, no combined predicted-effort figure, no Gantt. Finance and delivery tools own these. |

Principle 10 is a deliberate scope decision made during design review. Cost, budget and
initiative tracking were built and then **removed**. Do not reintroduce them without an
explicit decision — they are listed in §13 as out of scope.

---

## 2. Architecture

### 2.1 Shape: modular monolith

**Decision: a single Spring Boot deployable with enforced internal module boundaries.
Not microservices.**

Rationale:

- The core value is **cross-cutting graph queries**. Duplicate detection compares
  requirements across apps and products; impact analysis walks the trace graph in every
  direction. Distributing that across services turns single queries into chatty
  orchestration for no benefit.
- Requirement edits, revision creation, trace invalidation and audit writes must be
  **one transaction**. Distributed transactions to achieve what Postgres gives free is a
  poor trade.
- Team size does not justify the operational surface of microservices.

Boundaries are enforced in code so extraction stays cheap later:

```
com.vyoog
├── platform/        cross-cutting: tenancy, security, audit, events, errors
├── identity/        users, access grants, service accounts
├── portfolio/       products, apps, capabilities, glossary
├── requirements/    requirements, revisions, criteria, documents, import, changes
├── trace/           trace links, closure table, impact, matrix
├── design/          flows, nodes, edges, node↔requirement links
├── detection/       the twelve gap detectors, findings, rules
├── quality/         reviews, test cases, verification, defects
├── delivery/        briefs, scope signals
├── releases/        baselines, variants, environments, deployments, release notes
├── integration/     git, delivery tool, CI, HR, planning-tool connectors
└── notification/    inbox, digests
```

Enforce with **ArchUnit** in the test suite:

- No module may import another module's `internal` package.
- Modules communicate through published interfaces in `api` packages, or through
  Spring application events for anything asynchronous.
- Only `platform` may be imported by everyone.

### 2.2 Runtime topology

```
                    ┌──────────────┐
   Browser ────────▶│  React SPA   │  (static, served by CDN or nginx)
                    └──────┬───────┘
                           │ OIDC PKCE (Keycloak)
                           │ Bearer JWT
                    ┌──────▼───────┐        ┌────────────┐
                    │ Spring Boot  │───────▶│ Keycloak   │ (JWKS, admin API)
                    │   (API)      │        └────────────┘
                    └──────┬───────┘
                           │
              ┌────────────┼────────────┐
              │            │            │
        ┌─────▼─────┐ ┌────▼─────┐ ┌────▼──────┐
        │PostgreSQL │ │  Redis   │ │ Object    │
        │+ pgvector │ │ (cache,  │ │ store     │
        │           │ │  locks)  │ │(attachmt) │
        └───────────┘ └──────────┘ └───────────┘
                           │
                    ┌──────▼───────┐
                    │  Detection   │  (scheduled + event-driven,
                    │   workers    │   same deployable, separate profile)
                    └──────────────┘
```

Detection workers run in the same artifact under a `worker` Spring profile so they can be
scaled independently without a second codebase.

### 2.3 Technology choices, fixed

| Concern | Choice | Note |
|---|---|---|
| Language | Java 21 | Virtual threads for the IO-bound integration layer |
| Framework | Spring Boot 3.3+ | |
| Persistence | Spring Data JPA (Hibernate 6) + **jOOQ or JdbcTemplate for graph queries** | Recursive CTEs are not expressible in JPQL. Do not fight it. |
| Build | **Maven** (multi-module, with the Maven Wrapper) | See §2.4. Committed `mvnw`; no local Maven install required. |
| Migrations | **Flyway** | Versioned, forward-only. `V001__baseline.sql` etc. |
| DB | PostgreSQL 16 + `pgvector` + `pg_trgm` | pgvector for similarity, pg_trgm for fuzzy text search |
| Auth | Keycloak 25, OIDC, Authorization Code + PKCE | §4 |
| Cache / locks | Redis | Distributed lock for detection runs; short-lived caches |
| Object storage | S3-compatible (MinIO locally) | Attachments only; never requirement text |
| API style | REST, JSON, `/api/v1` | §9 |
| Frontend | React 18 + TypeScript + Vite | §10 |
| Server state | TanStack Query | |
| Grid | TanStack Table (headless) + custom rendering | The prototype's grid behaviour is the spec |
| Testing | JUnit 5, Testcontainers, REST Assured, Vitest, Playwright | §11.3 |
| Observability | Micrometer → Prometheus, OpenTelemetry traces, structured JSON logs | |

**Do not** introduce Neo4j or a separate graph database. See §5.4 — Postgres recursive
CTEs plus a closure table handle this workload, and keeping one datastore keeps
transactions honest.

### 2.4 Maven module layout

The Spring modules in §2.1 are **Java packages inside one deployable**, not separate
Maven artifacts. Splitting every domain module into its own jar buys nothing here and
costs a slow reactor build and constant version churn.

Four Maven modules, each with a real reason to exist:

```
vyoog/
├── pom.xml              parent · dependencyManagement, plugin versions, Java 21
├── vyoog-domain/        entities, repositories, services, detectors
│                        └── all com.vyoog.<module> packages live here
├── vyoog-api/           controllers, security, OpenAPI, the Spring Boot application
├── vyoog-worker/        detection scheduler, outbox relay, embedding jobs
│                        (same artifact deployed with a different profile)
└── vyoog-testkit/       Testcontainers fixtures, tenant-isolation harness, builders
                         (test-scoped, depended on by domain and api)
```

Full parent and module POMs are in **`vyoog-maven-project-setup.md`**.

Rules:

- Internal boundaries between the ten domain modules are enforced by **ArchUnit**, not by
  Maven. If a boundary needs extracting later, promote that package to its own module
  then — not speculatively now.
- `vyoog-domain` must not depend on `spring-boot-starter-web`. If a domain class needs
  an HTTP type, the design is wrong.
- Versions live only in the parent `dependencyManagement`. A child POM with a `<version>`
  on a managed dependency fails review.

---

## 3. Multi-tenancy

> **Note on your existing Vyoog PMS.** I have not seen that project, so what follows is
> designed from first principles for this system. §3.6 lists exactly what must be
> reconciled if your PMS already has a tenant model. Do not adopt this section blindly
> over a working model — reconcile first.

### 3.1 Decision

**Shared database, shared schema, `tenant_id` discriminator column, enforced by
PostgreSQL Row-Level Security.**

With one documented escape hatch: a tenant that contractually requires physical
isolation is moved to its own database with the identical schema, addressed by a routing
datasource. The application code does not change — only the connection resolution does.

### 3.2 Why this and not the alternatives

| Model | Verdict | Reason |
|---|---|---|
| Database per tenant | Escape hatch only | Migration cost multiplies per tenant. Connection pool per tenant exhausts Postgres. Justified only for contractual isolation. |
| Schema per tenant | Rejected | Same migration multiplication. `search_path` juggling on pooled connections is a reliable source of cross-tenant leaks. Postgres degrades with thousands of schemas. |
| **Shared schema + `tenant_id` + RLS** | **Chosen** | One migration. One pool. Isolation enforced by the database, so a forgotten `WHERE` clause cannot leak. |

The decisive argument is **defence in depth**. In an application-filter-only design, one
missing predicate in one query is a cross-tenant data breach. With RLS, the database
refuses regardless of what the application forgot.

### 3.3 Implementation

**Every tenant-owned table carries:**

```sql
tenant_id UUID NOT NULL REFERENCES tenant(id)
```

**Every tenant-owned table enables RLS:**

```sql
ALTER TABLE requirement ENABLE ROW LEVEL SECURITY;
ALTER TABLE requirement FORCE ROW LEVEL SECURITY;   -- applies to table owner too

CREATE POLICY tenant_isolation ON requirement
  USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

`FORCE` matters: without it the table owner bypasses the policy, and your application
user is often the owner.

**Setting the context — the part that is easy to get wrong.**

The tenant must be set with `SET LOCAL`, inside the transaction, so it is discarded when
the transaction ends and cannot leak to the next borrower of a pooled connection.

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantConnectionInitializer {

  // Bind on every transaction start, not on request start.
  // A pooled connection may be borrowed after the request-scoped filter ran.
  public void bind(Connection connection, UUID tenantId) throws SQLException {
    try (PreparedStatement ps =
             connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
      ps.setString(1, tenantId.toString());   // `true` = SET LOCAL semantics
      ps.execute();
    }
  }
}
```

Wire it via a `DataSource` proxy or an `AbstractRoutingDataSource` that decorates
`getConnection()`, **not** via a servlet filter alone.

**Required hardening:**

- The application's DB role must be **non-superuser and not the table owner**, or RLS is
  bypassed. Create `vyoog_app` explicitly with only DML grants.
- A dedicated `vyoog_migrator` role owns the schema and runs Flyway. It bypasses RLS,
  which is correct for migrations and unacceptable for request handling.
- Add an integration test that asserts a query without `app.tenant_id` set returns
  **zero rows**, not all rows. Run it in CI. This is the single most valuable test in the
  suite.

**Hibernate:** use Hibernate 6's `@TenantId` on the entity field so inserts populate it
automatically and queries filter it, giving a second layer above RLS.

### 3.4 What is *not* tenant-scoped

A small set of tables are platform-global and must **not** carry `tenant_id`:

- `tenant` itself
- `gap_rule_template` — the twelve built-in detector definitions
- `requirement_type`, `status`, `priority` reference data — Vyoog's own taxonomy,
  identical for every organisation
- `flyway_schema_history`

Tenants may *extend* reference data; those extensions live in tenant-scoped override
tables.

### 3.5 Tenant lifecycle

| Operation | Behaviour |
|---|---|
| Provision | Create `tenant` row → create Keycloak group/IdP mapping (§4.2) → seed the twelve gap rules from template → seed default roles → create the first Administrator grant |
| Suspend | `tenant.status = SUSPENDED`. Authentication succeeds, all API calls return `403 tenant_suspended`. Data untouched. |
| Export | Full tenant export as JSON + attachments archive. Required for enterprise contracts. Build it in Phase 5, not later. |
| Delete | Soft delete with a retention window, then hard delete via a job that walks tables in FK order. Must be tested — cascading deletes across the trace graph are easy to get wrong. |

### 3.6 Reconciling with your existing Vyoog PMS

Before adopting this, answer these. Where the PMS already made a choice, the PMS almost
certainly wins for consistency — two tenancy models in one product family is a
maintenance and security problem.

1. **Isolation level.** Does the PMS use shared-schema, schema-per-tenant, or
   database-per-tenant? If it uses schema-per-tenant, this spec should change to match
   rather than the reverse.
2. **Tenant identifier.** UUID or a human-readable slug? Is it in the URL path
   (`/t/{slug}/...`), a subdomain (`{slug}.vyoog.com`), or only in the token? Subdomain
   routing changes the Keycloak redirect-URI configuration significantly.
3. **Is the tenant the customer, or a workspace inside a customer?** Some PMS products
   nest organisation → workspace → project. If Vyoog PMS does, then `tenant_id` here may
   need to be `workspace_id`, with an `organisation_id` above it.
4. **Does a user belong to exactly one tenant, or many?** Consultants and auditors
   commonly need many. If many, the JWT cannot carry a single `tenant_id` — it carries a
   set, and the active tenant becomes a per-request header validated against that set.
   **This materially changes §4.3.** Decide it before writing the security layer.
5. **Shared Keycloak realm with the PMS?** If both products authenticate the same
   humans, they should share a realm and differ by client. That affects role naming
   (`vyoog-rm:*` vs `vyoog-pms:*`).
6. **Cross-product data.** Does the PMS own the customer/project record that Vyoog's
   Product should point at? If so, Product gets an `external_ref` and the PMS becomes the
   system of record for portfolio structure — which changes §7.3 from an authoring
   screen to a read-mostly mirror.

### 3.7 Reviewing `evyoog/vyg-pms` — the files that answer the questions above

Both repositories are **private**, so they cannot be fetched directly. The following
short list is enough to answer every question in §3.6 and to review the tenant setup
properly. **Redact secrets before sharing** — passwords, client secrets, and any real
`issuer-uri` credentials.

**Backend — `vyg-pms`**

| File / pattern | What it settles |
|---|---|
| `pom.xml` (parent and modules) | Spring Boot version, Hibernate version, whether `hibernate-core` multitenancy is used, Flyway vs Liquibase |
| `src/main/resources/application*.yml` | Datasource, JPA settings, `multiTenancy` mode, Keycloak issuer and audience |
| `**/*Tenant*.java` | The whole tenant mechanism — resolver, context, filter, interceptor |
| `**/CurrentTenantIdentifierResolver*`, `**/MultiTenantConnectionProvider*` | Whether Hibernate `SCHEMA` or `DATABASE` multitenancy is in use. **If either exists, this specification's §3.1 must change to match.** |
| `**/SecurityConfig.java`, `**/*JwtAuthConverter*`, `**/Keycloak*.java` | Realm topology, how roles map, whether authorization is in Keycloak or the app |
| `src/main/resources/db/migration/` (file listing + `V1__*.sql`) | Whether tables carry `tenant_id`, and whether RLS is used at all |
| One `@Entity` base class (`BaseEntity` / `AuditableEntity`) | How `tenant_id` is modelled and populated |
| `docker-compose.yml`, any `realm-export.json` | Keycloak realm, clients, roles, IdPs as actually configured |

**Frontend — `vyg-pms-ui`**

| File | What it settles |
|---|---|
| `package.json` | React version, whether `keycloak-js` or `oidc-client-ts` |
| `**/keycloak*.ts`, `**/auth*.ts` | Init flow, PKCE, token storage (check: is it in `localStorage`?) |
| The axios/fetch interceptor | Whether a tenant header is sent, and where it comes from |
| `.env.example` | Realm, client id, redirect URIs |

**What the review will produce:** a written comparison of the PMS tenant model against
§3.1–§3.5, a list of conflicts, and a recommendation on which model both products should
share. Where the PMS already works, the PMS wins — running two tenancy models across one
product family is a security and maintenance liability.

---

## 4. Keycloak and authorization

### 4.1 Division of responsibility

| Concern | Owner |
|---|---|
| Authentication, MFA, session, password policy, IdP federation | **Keycloak** |
| Coarse platform roles (`platform-admin`, `tenant-admin`, `user`, `service`) | **Keycloak** |
| Fine-grained authorization: role × scope | **Vyoog** (`access_grant` table) |

**Why fine-grained authorization is not in Keycloak.** Principle 9 requires grants like
"Business Analyst **on** Valam ▸ HR Intelligence". Expressing that in Keycloak means
either a role per scope (thousands of roles) or deep group paths, and either way it
lands in the access token and bloats every request. Scopes also change constantly — a new
capability should not require a Keycloak admin call. Vyoog owns this; Keycloak stays the
identity authority.

### 4.2 Realm topology

**Chosen: a single realm per environment, with one Identity Provider alias per customer
that federates their corporate SSO.** Home-IdP discovery by email domain routes the user.

Rejected alternative: realm-per-tenant. It gives stronger isolation and per-tenant
policy, but the issuer differs per tenant (complicating token validation), Keycloak
admin overhead grows linearly, and platform-wide administration becomes awkward. Revisit
only when a customer contractually demands an isolated realm — at which point that
tenant also moves to the isolated-database escape hatch in §3.1, and both changes ship
together.

```
realm: vyoog
├── clients
│   ├── vyoog-web        public,       Authorization Code + PKCE
│   ├── vyoog-api        bearer-only,  audience for access tokens
│   └── vyoog-service    confidential, client-credentials for integrations
├── realm roles
│   ├── platform-admin   cross-tenant, Vyoog staff only
│   ├── tenant-admin
│   ├── user
│   └── service-account
├── identity providers
│   ├── acme-saml        (customer A corporate SSO)
│   └── globex-oidc      (customer B)
└── client scopes
    └── vyoog-tenant     → adds the tenant claim (§4.3)
```

### 4.3 Token claims

Add a protocol mapper on the `vyoog-tenant` client scope producing:

```jsonc
{
  "sub": "8f14e45f-...",
  "email": "j.alvarez@acme.com",
  "preferred_username": "j.alvarez",
  "vyoog_tenant": "3b1f...",        // active tenant UUID
  "vyoog_tenants": ["3b1f...", "9a22..."],  // only if multi-tenant users are supported
  "realm_access": { "roles": ["user"] },
  "aud": "vyoog-api"
}
```

**Server-side rules, all mandatory:**

1. Validate `iss`, `aud`, `exp`, and the signature against JWKS. Cache JWKS; honour
   rotation.
2. Resolve `tenant_id` **from the token**, never from a request header, body or path
   parameter that the client controls — unless multi-tenant users are supported, in
   which case an `X-Vyoog-Tenant` header is accepted **only if it is a member of
   `vyoog_tenants`**, and rejected otherwise.
3. On first sight of a `sub`, upsert an `app_user` row (a local mirror; Keycloak stays
   the source of truth for identity attributes).
4. Bind `app.tenant_id` for the transaction (§3.3) before any repository call.

### 4.4 The role × scope model

```
grant = (user, role, scope_type, scope_id)

scope_type ∈ { TENANT, PRODUCT, APP, CAPABILITY, RELEASE }
```

A grant at `PRODUCT` implies the same role on every app and capability beneath it.
Resolution walks *up* the hierarchy from the target object and returns true on the first
matching grant.

**Roles and their permissions** (the matrix rendered on Administration → Roles):

| Role | Read | Create req | Edit req | Review | Approve | Verify | Baseline | Admin |
|---|---|---|---|---|---|---|---|---|
| Viewer | ✓ | | | | | | | |
| Business Analyst | ✓ | ✓ | ✓ | | | | | |
| Reviewer | ✓ | | | ✓ | | | | |
| Approver / Product Owner | ✓ | | | ✓ | ✓ | | ✓ | |
| Developer | ✓ | | | | | | | |
| QA / Tester | ✓ | | | | | ✓ | | |
| Compliance Lead | ✓ | | | ✓ | | | | |
| Architect | ✓ | ✓ | ✓ | ✓ | | | | |
| Administrator | ✓ | | | | | | | ✓ |

**Separation of duties**, enforced and surfaced on Administration → Security:

- A user may not approve a requirement they own or authored. Detect and block at the
  service layer; report existing violations as findings.
- Administrator combined with any sign-off role is a reportable finding.
- Service accounts may never approve, sign off, or administer. Enforce by rejecting
  those endpoints for any principal whose token has `service-account` in `realm_access`.

### 4.5 Step-up authentication

Approval and baseline freeze are signature events. Require a second factor **at the
moment of the action**, not merely at sign-in:

- Request the action with the current token.
- If `acr` is below the required level, return `401` with
  `WWW-Authenticate: Bearer error="insufficient_user_authentication", acr_values="silver"`.
- The SPA re-authenticates with `acr_values=silver` and retries.

Record the resulting `acr` and `auth_time` on the signature record.

### 4.6 Service accounts

Integrations authenticate with client credentials against `vyoog-service`. Each
integration gets its own client, its own secret, and the **narrowest scope that works**:

| Service account | Scopes |
|---|---|
| `git-webhook` | `link:write` |
| `ci-test-reporter` | `test:write` |
| `delivery-sync` | `req:read link:write status:read` |
| `export-job` | `req:read export` |

Key age is tracked and surfaced; rotation is a first-class endpoint. This is exactly what
Administration → Service accounts renders.

---

## 5. Data model

Full DDL is in `vyoog-schema.sql`. This section explains the decisions that DDL encodes.

### 5.1 Conventions

- Primary keys: `UUID` (v7 where available, for index locality).
- Every tenant-owned table: `tenant_id UUID NOT NULL`, RLS enabled.
- Audit columns on every table: `created_at`, `created_by`, `updated_at`, `updated_by`.
- Soft delete via `deleted_at` only where a record must survive for audit
  (requirements, findings, grants). Everything else deletes hard.
- Money and duration columns: **none anywhere** (Principle 10).
- Timestamps: `TIMESTAMPTZ`, always UTC.

### 5.2 Core entities

```
tenant

app_user                  mirror of a Keycloak subject
access_grant              user × role × scope        ← Principle 9
service_account

product
application               ("app" is a reserved word in too many places)
capability

requirement               the current, mutable head row
requirement_revision      immutable snapshot, one per save
acceptance_criterion      child of requirement, ordered
requirement_comment
attachment / attachment_version

trace_link                the graph edge
trace_closure             materialised reachability      ← §5.4
```

### 5.3 Requirement, revision, and why "Verified" evidence is strict (but not a status)

`requirement` holds the current state. Every save writes a `requirement_revision` row
and increments `requirement.revision`. Revisions are never updated or deleted.

```sql
requirement (
  id, tenant_id, key,            -- key = human ID, e.g. 'VY-1042', unique per tenant
  capability_id, type, status, priority,
  title, statement,
  owner_id, target_release_id,
  revision      INT NOT NULL DEFAULT 1,
  quality_score SMALLINT,
  ...
)

requirement_revision (
  id, tenant_id, requirement_id,
  revision INT NOT NULL,
  statement TEXT NOT NULL,
  type, status, priority, capability_id,   -- full snapshot, not a diff
  changed_by, changed_at, change_reason,
  UNIQUE (requirement_id, revision)
)
```

**Verification is bound to a revision:**

```sql
verification (
  id, tenant_id,
  requirement_id,
  requirement_revision INT NOT NULL,   -- the revision that was actually tested
  test_case_id,
  test_run_id,
  result        -- PASS | FAIL
  verified_at
)
```

`is_verified` (below) is true when a `verification` row exists with `result = PASS`
**and** `requirement_revision = requirement.revision`. Bump the revision and it silently
stops being true — no job required, it falls out of the predicate. This is Principle 5,
and implementing it as a stored flag instead of a derived predicate is the most likely
way to get it wrong.

**D16: this predicate does not drive `requirement.status`.** It used to — a passing test
promoted APPROVED straight to a `VERIFIED` status — but that coupling was removed.
APPROVED is now the requirement lifecycle's terminal status, and "verifying" a
requirement is a person's action on an IN_REVIEW one (see §8, `IN_REVIEW → REVIEWED`),
not something CI reports back automatically. `is_verified`/`has_stale_evidence` are still
real and still used — by `ReleaseService#readiness`, and by Quality → Verification — as
quality-reporting facts about a requirement, independent of what stage it is at.

Expose it as a view:

```sql
CREATE VIEW requirement_verification_state AS
SELECT r.id, r.tenant_id,
       EXISTS (SELECT 1 FROM verification v
                WHERE v.requirement_id = r.id
                  AND v.requirement_revision = r.revision
                  AND v.result = 'PASS')  AS is_verified,
       EXISTS (SELECT 1 FROM verification v
                WHERE v.requirement_id = r.id
                  AND v.requirement_revision < r.revision
                  AND v.result = 'PASS')  AS has_stale_evidence
FROM requirement r;
```

`has_stale_evidence` is what Quality → Verification counts as "stale evidence".

### 5.4 The trace graph: edge table plus closure table

This is decision **A1** and it was contested. The answer is Postgres, not Neo4j.

```sql
trace_link (
  id, tenant_id,
  from_type, from_id,      -- NEED | REQUIREMENT | DESIGN_NODE | CODE | TEST | RELEASE
  to_type,   to_id,
  link_type,               -- SATISFIES | DERIVES | VERIFIES | IMPLEMENTS | REFINES | CONFLICTS
  created_by, created_at,
  reviewed_at_revision INT,   -- upstream revision when this link was last reviewed
  UNIQUE (tenant_id, from_type, from_id, to_type, to_id, link_type)
)
```

**`reviewed_at_revision` is what makes suspect-link detection possible.** When the
upstream item's revision exceeds this value, the link is suspect — the dependant was
never revisited after its parent changed. Detector 6 is one comparison.

**Reachability** is needed constantly (impact analysis, orphan detection, coverage
rollups). Two mechanisms, used for different jobs:

*Recursive CTE* for ad-hoc traversal, depth-limited:

```sql
WITH RECURSIVE downstream AS (
  SELECT to_type, to_id, 1 AS depth
    FROM trace_link
   WHERE from_type = 'REQUIREMENT' AND from_id = :id
  UNION ALL
  SELECT tl.to_type, tl.to_id, d.depth + 1
    FROM trace_link tl
    JOIN downstream d ON tl.from_type = d.to_type AND tl.from_id = d.to_id
   WHERE d.depth < 12
)
SELECT DISTINCT * FROM downstream;
```

The `depth < 12` guard is mandatory. Requirement graphs contain cycles in practice
(A refines B, B conflicts with A) and an unguarded CTE will not terminate.

*Closure table* for hot read paths — coverage percentages, grid pips, dashboard rollups:

```sql
trace_closure (
  tenant_id, ancestor_type, ancestor_id, descendant_type, descendant_id,
  depth SMALLINT,
  PRIMARY KEY (tenant_id, ancestor_type, ancestor_id, descendant_type, descendant_id)
)
```

Maintained incrementally on link insert/delete inside the same transaction. Rebuildable
from `trace_link` by a maintenance job; add a nightly consistency check that compares a
sample and alerts on drift.

**Why not Neo4j:** the graph is small (hundreds of thousands of edges, not billions),
every traversal is tenant-scoped and depth-limited, and the alternative costs a second
datastore, a second consistency model, and distributed transactions between the register
and the graph. Postgres wins on every axis that matters here.

### 5.5 Remaining tables by area

**Portfolio** — `product`, `application`, `capability`, `glossary_term`,
`glossary_term_usage`

**Documents and intake** — `document`, `document_section`, `import_batch`,
`import_candidate` (with `candidate_flag` child rows for detector output),
`change_request`, `change_impact`

**Design** — `design_flow` (one per application), `design_node`, `design_edge`,
`design_node_requirement`

**Detection** — `gap_rule_template` (global), `gap_rule` (tenant, enable/disable/tune),
`finding`, `finding_state` (OPEN / ACCEPTED / DISMISSED with reason and actor),
`requirement_embedding` (pgvector)

**Quality** — `review`, `review_item`, `review_participant`, `review_comment`,
`test_case`, `test_run`, `test_result`, `verification`, `defect`

**Delivery** — `brief`, `brief_requirement`, `brief_target`

**Releases** — `release`, `release_scope_item`, `scope_movement`, `baseline`,
`baseline_item`, `variant`, `variant_applicability`, `environment`, `deployment`,
`deployment_requirement`, `release_note`

**Collaboration** — `clarification` (§7.4.7), `notification`, `inbox_item`

**Platform** — `audit_event`, `integration_connection`, `outbox_event`

### 5.6 Audit

```sql
audit_event (
  id, tenant_id, occurred_at,
  actor_id, actor_type,        -- USER | SERVICE | SYSTEM
  action,                      -- REQUIREMENT_APPROVED, GRANT_REVOKED, ...
  object_type, object_id,
  before JSONB, after JSONB,
  request_id, ip, user_agent
)
```

Append-only, enforced by a `BEFORE UPDATE OR DELETE` trigger that raises an exception.
Partition monthly. Never expose a delete endpoint.

### 5.7 Indexing that actually matters

```sql
-- every tenant-scoped lookup leads with tenant_id
CREATE INDEX ON requirement (tenant_id, capability_id, status);
CREATE INDEX ON requirement (tenant_id, owner_id) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX ON requirement (tenant_id, key);

-- graph traversal in both directions
CREATE INDEX ON trace_link (tenant_id, from_type, from_id);
CREATE INDEX ON trace_link (tenant_id, to_type,   to_id);

-- fuzzy text search for duplicate candidates and the grid's text filter
CREATE INDEX ON requirement USING gin (statement gin_trgm_ops);

-- similarity search
CREATE INDEX ON requirement_embedding
  USING hnsw (embedding vector_cosine_ops);

-- open findings, the most-read query in the product
CREATE INDEX ON finding (tenant_id, rule_key, severity)
  WHERE state = 'OPEN';
```

---

## 6. Gap detection — the differentiator

Twelve detectors. Each has a key, a technique, and a severity. **Seven need no AI at
all** — build those first and ship a genuinely useful product before any model is
involved.

| # | Key | Name | Technique | Severity | Phase |
|---|---|---|---|---|---|
| 1 | `noverify` | Missing verification | GRAPH | crit | 1 |
| 2 | `ambig` | Unmeasurable wording | RULE | ai | 1 |
| 3 | `orphan` | Orphan — no upstream need | GRAPH | crit | 1 |
| 4 | `nodesign` | No downstream design | GRAPH | high | 1 |
| 5 | `noac` | No acceptance criteria | GRAPH | high | 1 |
| 6 | `suspect` | Suspect link | GRAPH | high | 1 |
| 7 | `compl` | Unmapped control clause | EMBED | crit | 4 |
| 8 | `untraced` | Untraced code change | GRAPH | crit | 2 |
| 9 | `dup` | Duplicate across apps | EMBED | ai | 4 |
| 10 | `conflict` | Conflicting requirements | LLM | crit | 4 |
| 11 | `errpath` | Happy path only | CLASS | high | 4 |
| 12 | `nonfr` | Missing NFR counterpart | LLM | ai | 4 |

### 6.1 Detector contract

```java
public interface GapDetector {
  String key();
  Technique technique();
  /** Pure function of tenant state → findings. Must be idempotent. */
  List<FindingCandidate> detect(DetectionContext ctx);
}
```

Rules:

- A detector **never writes** to domain tables. It emits candidates; the engine
  reconciles them against existing findings.
- Reconciliation is by **stable fingerprint**, not row identity:
  `sha256(rule_key | object_type | object_id | discriminator)`. Same fingerprint on a
  later run means the same finding — do not create a duplicate, do not resurrect a
  dismissal.
- A finding whose fingerprint disappears from a run is **auto-resolved** with
  `resolution = FIXED`.
- A dismissed finding stays dismissed unless the underlying object's revision changes.
  Dismissal carries a reason and an actor — those reasons are what produce the published
  false-positive rate (Principle 7).

### 6.2 The seven no-AI detectors, as SQL

```sql
-- 1. noverify: approved but no passing test against the current revision. VERIFIED is
-- not a status (D16) — APPROVED is the pipeline's terminal one, so it is the only
-- status this needs to check.
SELECT r.id FROM requirement r
 WHERE r.status = 'APPROVED'
   AND NOT EXISTS (SELECT 1 FROM verification v
                    WHERE v.requirement_id = r.id
                      AND v.requirement_revision = r.revision
                      AND v.result = 'PASS');

-- 3. orphan: nothing upstream satisfies a need
SELECT r.id FROM requirement r
 WHERE NOT EXISTS (SELECT 1 FROM trace_link t
                    WHERE t.to_type = 'REQUIREMENT' AND t.to_id = r.id
                      AND t.link_type IN ('SATISFIES','DERIVES'));

-- 4. nodesign: no design node implements it
SELECT r.id FROM requirement r
 WHERE NOT EXISTS (SELECT 1 FROM design_node_requirement d
                    WHERE d.requirement_id = r.id);

-- 5. noac
SELECT r.id FROM requirement r
 WHERE NOT EXISTS (SELECT 1 FROM acceptance_criterion a
                    WHERE a.requirement_id = r.id);

-- 6. suspect: upstream moved after the link was last reviewed
SELECT t.id FROM trace_link t
  JOIN requirement up ON up.id = t.from_id AND t.from_type = 'REQUIREMENT'
 WHERE t.reviewed_at_revision IS NOT NULL
   AND up.revision > t.reviewed_at_revision;

-- 8. untraced: a commit with no requirement trailer
SELECT c.id FROM code_change c
 WHERE NOT EXISTS (SELECT 1 FROM trace_link t
                    WHERE t.from_type = 'CODE' AND t.from_id = c.id);
```

Detector 2 (`ambig`) is a lexicon plus regex, not a model. **The prototype's
implementation is the specification** — port `AMBIG_TERMS`, the weak-modal check, the
compound-requirement heuristic and the length check from `VyoogApp.jsx` verbatim,
including the suggested replacement for each term. It already detects: `promptly`,
`quickly`, `appropriate`, `adequate`, `sufficient`, `reasonable`, `efficient`,
`user-friendly`, `intuitive`, `seamless`, `robust`, `flexible`, `scalable`, `optimal`,
`minimal`, `several`, `many`, `few`, `various`, `etc`, `approximately`, `improved`,
`better`, `easy`, `easily`, `simple`, `simply`, `quick`, `fast`, `clearly`, `properly`,
`correctly`, `acceptable`, `significant`, `where possible`, `if possible`, `up to date`,
`timely`, `regularly`, `periodically`, `as needed`, `as required`, `if necessary`,
`state-of-the-art`, `best practice`, `and so on`.

### 6.3 The AI detectors (Phase 4)

**Embedding pipeline.** On requirement save, enqueue an embedding job. Store in
`requirement_embedding` with the model name and the revision embedded, so a model change
is a re-embed and not silent drift.

- **`dup`** — cosine similarity ≥ 0.85 between requirements in *different* apps.
  Below 0.85 down to 0.70, surface only on the create screen as a soft hint.
- **`compl`** — embed control clauses; a clause with no requirement above 0.75 is
  unmapped.
- **`conflict`** — embeddings shortlist candidate pairs above 0.80; an LLM adjudicates
  whether they genuinely contradict. Never auto-create; always a proposal.
- **`errpath`** — a small classifier, or a rule fallback: no error/failure vocabulary
  present. The prototype's `ERR_WORDS` list is the fallback specification.
- **`nonfr`** — a Functional requirement in a capability with no Non-Functional peer.
  Graph-detectable; LLM only improves the suggested counterpart text.

**Governance for every AI output:**

- Store the model, prompt version, and confidence with the finding.
- Confidence below threshold means it is not shown at all, rather than shown as weak.
- Every AI finding renders amber (Principle 3) and is never applied automatically.
- Track and publish the dismissal rate per detector. A detector above ~30% dismissal is
  disabled by default for new tenants until retuned.

### 6.4 When detection runs

| Trigger | Scope |
|---|---|
| Requirement created / updated / status change | That requirement plus its immediate graph neighbours |
| Trace link created / deleted | Both endpoints |
| Test result ingested | The requirement under test |
| Commit webhook | The referenced requirements, plus `untraced` for the commit |
| Nightly | Full tenant sweep; reconciles anything the event path missed |
| Manual "Rescan" | Full tenant sweep, rate-limited to one per five minutes |

Run under a Redis lock keyed by `tenant_id` so two sweeps cannot overlap. Emit
`DetectionRunCompleted` with counts per rule for the trend chart on Home.

---

## 7. Feature inventory

Ten modules. Every screen, tab and behaviour below exists in the prototype and is in
scope. Nothing here is optional unless marked so.

### 7.0 Cross-cutting: the application shell

| Feature | Behaviour |
|---|---|
| Sidebar | Ten modules, one divider before Administration. Collapsible. Per-item badge counts. |
| **Role-based visibility** | A module is hidden entirely if the user holds no grant permitting it. Driven by `access_grant`, not a hardcoded list. |
| Command palette | `⌘K` / `Ctrl+K`. "Go to" entries generated from the nav; "Actions" entries for create/import/generate/rescan/baseline/glossary/workflow. Fuzzy match. |
| Theme | Dark and light. Full token set for both. Persisted per user. |
| Global search | Across requirements, capabilities, glossary, findings. Trigram-backed. |
| Notification inbox | Unread count in the header; items link to the object. |
| Toasts with undo | Every mutation that can be reversed shows a toast with an **Undo** action for 7 seconds. Undo is a real inverse operation, not a UI trick. |
| Confirm dialogs | For destructive or consequential actions. Title, body, an explicit list of what is affected, a named danger button. |
| Breadcrumb | Portfolio → Product → App → Capability where applicable. |

**Design tokens.** Port the CSS custom properties from the prototype exactly — both
token sets. Amber is reserved for AI (Principle 3); enforce this with a lint rule that
fails the build if `--ai` is used on a status element.

### 7.1 Home

Three tabs. Every figure derives from live data; nothing is stored.

- **Now** — stat row (requirements, open gaps, approval coverage, release
  readiness); *Blocking this release* (critical findings, click through to Analytics);
  *Requirement flow this sprint* (Draft → In review → Reviewed → Approved, with a "stuck"
  break where the drop-off is largest); *Your queue* pointer to My Work — deliberately a
  pointer, not a second copy; *Not owned here* (external systems and their connection
  state); *Coverage by product*.
- **Trends** — open gaps per baseline over time; coverage by product.
- **Coverage map** — product × lifecycle-stage heatmap.

### 7.2 My Work

**Tasks are derived, never authored** (Principle 6). This module is a query.

- **Today** — overdue / today / this week / blocked, grouped. Each row states *why* it
  is a task ("Approved 6 days ago, no code linked yet"). Check-off is optimistic.
- **Pipeline** — the requirement's own lifecycle as lanes (Draft, In review, Approval,
  Development, Verification, Deployed), not a sprint board. Cards mark themselves stalled
  past a per-stage threshold.
- **Calendar** — month view; personal task due dates merged with release milestones.

Derivation rules (implement as one SQL view per rule):

| Task | Rule |
|---|---|
| Author: fix wording | Requirement owned by user with an open `ambig` finding |
| Reviewer: review | Requirement in review with user as participant |
| Approver: sign off | Requirement `IN_REVIEW`, user holds Approver on its scope |
| Developer: implement | Requirement `APPROVED`, assigned developer, no code link |
| Developer: re-implement | Code links to revision N, requirement now at N+1 |
| Tester: verify | Requirement `APPROVED` with no passing test at current revision |
| Tester: re-verify | Evidence stale (`has_stale_evidence`) |
| Anyone: unblock | Open clarification blocking a task the user owns |

### 7.3 Portfolio

- **Products** — cards with requirement count, gap count, coverage. Drill to app list,
  then capability list. Create/edit product, app, capability.
- **Glossary** — term, definition, owner, apps used in, conflict flag. A term defined
  differently in two apps is a conflict and is surfaced.

### 7.4 Requirements

The largest module.

#### 7.4.1 Grid

The data grid is a substantial component in its own right. Required behaviours, all
present in the prototype:

- Column show/hide, **drag to reorder**, resize, pin left
- Sort, multi-column filter (text and select per column type)
- **Group by** capability / status / owner / priority / type / release, collapsible
- Row density: compact / normal / relaxed
- Row selection, select-all-in-filter
- **Inline cell edit** — double-click, `Tab` commits and moves to the next editable cell,
  `Esc` reverts
- **Bulk edit modal** — change priority/status/type/capability/release/owner across a
  selection; "leave unchanged" is the default for every field; writes to each
  requirement's history; undoable as one action
- **Coverage pips** `U D C T` on every row — upstream, design, code, test. Red where
  missing (Principle 2)
- Quality score column; gap count column
- Expand row for inline detail
- Virtualised rendering — the register reaches tens of thousands of rows
- Server-side data source behind a single interface so paging/sorting/filtering move to
  the backend without touching the component

#### 7.4.2 Detail panel

Attributes; statement editor with autosave draft; acceptance criteria; **attachments with
versioning** (never overwrite, keep every version); traceability up and down;
**lifecycle and accountability timeline** (who authored, reviewed, approved, developed,
tested, deployed — gated by status rank so nothing claims a stage that has not happened);
discussion thread; *Raise clarification*; *Raise defect*.

**Concurrent-edit protection.** The panel shows who else has the requirement open. On
save, the server compares the client's revision. On mismatch it returns `409` with both
versions, and the UI presents a three-way choice: keep theirs, keep mine, or merge. Both
versions remain in history regardless. Never last-write-wins.

#### 7.4.3 New requirement screen

A full authoring screen, not a modal. Built and specified in the prototype.

- Placement: Product → App → Capability, cascading; unplaced is allowed but warned
- Statement with **live linting** — the `ambig` detector runs as you type, plus missing
  `shall`, weak modals, compound requirements, excessive length
- Each ambiguous term shows an inline suggested replacement; **never auto-applied**
- Classification: type, priority, target release, owner
- Acceptance criteria: repeatable rows
- Traceability: upstream need required; downstream is read-only ("links are made as work
  happens, never typed here")
- **Live gap preview** — which of the twelve will fire on save, split into *avoidable*
  and *expected* (`noverify` and `nodesign` always fire on a new requirement and are
  shown greyed, not alarming)
- Live quality score ring
- **Duplicate detection** against the register as you type
- Save as draft · Save and add another (keeps placement) · Submit for review
- Confirm on submit **only** when it would waste a reviewer's time (no criteria, or score
  below 60) — the rail already lists the gaps, so a modal repeating them is noise

#### 7.4.4 Documents

Register of specification documents (title, ID, product, item count, revision, status,
gaps, updated). Document view renders requirements as readable prose rather than a grid.
Export to Word and ReqIF.

#### 7.4.5 Matrix

Requirement × test-case coverage matrix, four cell states (none / verified / linked-not-run
/ suspect), per-test totals in the footer, "show only gaps" filter. Second tab: suspect
links — upstream changed, dependants never revisited.

#### 7.4.6 Graph

Interactive trace graph from business need through requirement, design, code and test.
Chains that stop short are highlighted. Depth-limited; lazy-expand on click.

#### 7.4.7 Change requests and the Clarification object

**Change request** — a change to an approved requirement is itself an approvable item.
Impact volume is computed from the graph *before* anyone rules on it. On acceptance,
every downstream item is marked **suspect** rather than silently invalidated.

**Clarification** — fully designed, never built. Build it.

```
clarification (
  id, tenant_id, requirement_id, raised_by, raised_at,
  question TEXT,
  blocks_task BOOLEAN,          -- does this stop work?
  assigned_to,                  -- who must answer
  answered_by, answered_at, answer TEXT,
  state,                        -- OPEN | ANSWERED | WITHDRAWN
  resulted_in_change_request_id -- nullable
)
```

Rules: an open clarification with `blocks_task` blocks the derived task for its
requirement and shows in My Work as blocked. Ageing clarifications escalate. An answer
that changes meaning must produce a change request rather than an edit — enforce by
offering that path in the UI when the answer is accepted.

#### 7.4.8 Import queue

Upload a specification; the parser splits it into candidate requirements. Each candidate
shows extracted text (**editable inline before acceptance**), detected capability,
acceptance-criteria count, quality score, and detector flags with suggested fixes.
Accept / accept-with-fix / import-as-written / skip, per candidate. Bulk import of the
selection as Draft. Nothing enters the register without explicit acceptance.

Distinguish a **standard requirement document** upload type with stricter validation and
a separate capability-confirmation step.

### 7.5 Design

- Product → App selectors; one workflow per app
- Auto-laid-out flow diagram (layered by longest path from root; branches share a column)
- Node kinds: start, step, decision, integration, end, **testing, deployment**
  (VYB-0816). Integrations render dashed (Principle 4)
- **Node colour derives from the requirements behind it** — green when all approved
  (D16: APPROVED is the pipeline's terminal status), red when the node has no
  requirement at all. **Testing/Deployment are the exception**: their colour comes from
  real evidence, not requirement status — `requirement_verification_state.is_verified`
  for Testing, presence in `deployment_requirement` for Deployment — since an APPROVED
  requirement with no passing test is not "tested." Always drawn, never hidden; colour
  and the `n/total` sublabel carry how far along it is (Principle 8)
- **Generate from requirements** (VYB-0666) draws one node per approved-or-better
  requirement and wires edges from real trace links only — never an invented ordering
  (the flow stays authored, not inferred, per the rule below). It additionally ensures
  one shared Testing node and one shared Deployment node per flow, fed by every
  requirement node in turn (VYB-0816) — additive and idempotent, like the rest of this
  action
- Click a node: linked requirements, attach/detach, connections, delete
- Add step: label, kind, predecessor, branch label, requirement links
- **Coverage tab** — requirements with no design (feeds detector 4) and steps with no
  requirement (design nobody asked for)
- Export the diagram as SVG

The flow is **authored**, not inferred. Vyoog cannot derive a diagram from requirement
prose honestly; what is automatic is layout, colouring and coverage analysis.

### 7.6 Analytics (formerly Gap Radar)

- **Findings** — filterable by class, severity, scope, mine-only. Each finding shows the
  class, confidence where AI-derived, the objects involved, and a suggested action.
  Accept / dismiss-with-reason.
- **Lifecycle coverage spine** — seven stages, each break clickable to filter findings
  to that break.
- **Coverage map** — where the chain breaks, by product and stage.
- **Rules** — the twelve detectors: enable/disable per tenant, tune thresholds, view
  false-positive rate.
- **Dismissed** — every dismissal with its reason and actor.

### 7.7 Quality

- **Reviews** — review rounds (active / awaiting my signature / closed). Participants,
  progress, blocked state. Electronic signature on approval with step-up auth (§4.5).
- **Verification** — test cases linked to requirements; last run result; unverified
  requirements; **stale evidence** (changed after last pass). "Draft test case" action.
- **Defects** — every defect names the requirement it traces to **and why**: root cause
  classified as *requirement defect* (ambiguity, omission) versus *coding error*. This
  linkage is the loop that proves requirement quality affects production outcomes.

### 7.8 Delivery

- **Implementation briefs** — the feature the whole product points at.
  - Select app → capability(ies); the app selector genuinely drives the capability list
  - Choose target: **Claude Code / Codex / Human-readable** — this actually branches the
    generated content, not just a label
  - Assign a **developer at generation time**; named in the header, Section 0 and the
    Definition of Done
  - Options: include acceptance criteria, NFRs, trace IDs, glossary, test skeletons
  - Generated markdown includes: Section 0 (context and who is doing this),
    **Section 2 — AI review across all requirement categories in scope** (counts
    Functional / Non-Functional / Business Rule / Other, plus test coverage, and flags
    when no NFRs are present), Section 5 grouped by requirement category, Definition of
    Done, and a commit-trailer block (omitted for the human-readable target)
  - **AI elaboration (VYB-0817), explicit opt-in, off by default** — a real OpenAI call
    expands each requirement's statement into 3-6 sentences of detailed prose for the
    developer, grounded only in the statement and its acceptance criteria (never a new
    fact, threshold or system, never effort/cost/duration). Rendered directly under the
    requirement's own statement in Section 5, clearly labelled and never in its place —
    the statement is what a human wrote and approved; this only expands on it. Requesting
    it while the AI provider is unconfigured refuses the whole generation rather than
    silently returning a brief without it.
  - Download as `.md`
  - **Persist the brief as an object with a baseline reference** so staleness is
    detectable: if requirements change after generation, the brief is marked stale
- **Scope signals** — eight exact counts over the graph (requirements, acceptance
  criteria, dependency depth, cross-app reach, ambiguity load, open gaps, change rate,
  novelty), each shown against the portfolio median, with the SQL that computed it
  viewable. **No combined effort estimate, ever** — that is the delivery tool's job.
  Borrowed signals render hatched with a source tag.
- **Impact analysis** — pick a requirement, see volume affected: requirements, tests,
  apps, capabilities, teams, briefs made stale. Volume only, never days.

### 7.9 Releases

- **Scope** — what is committed per product; verified percentage; open gaps; scope
  movement in and out over the last 30 days with who moved it
- **Baselines** — immutable frozen snapshots; created date, revision, item count, gaps at
  freeze, signed off by. Compare any two baselines
- **Variants** — which requirements apply to which product edition
- **Deployment** — which requirements are present in which environment, at which build.
  Build numbers arrive from CI hatched; what Vyoog owns is the link between requirement,
  revision and build
- **Release notes** — generated from requirements that reached Approved (D16: the
  pipeline's terminal status). Anything still held short of it is **listed separately,
  never silently dropped**

### 7.10 Administration

- **Users** — directory, source (SSO / HR system / local invite), MFA state, last seen,
  status, grants per user
- **Access grants** — role × scope, add/revoke, expiry mandatory for external users
  (maximum 180 days)
- **Roles** — the permission matrix (§4.4), read-only display of what each role may do
- **Service accounts** — scopes, last used, key age, rotate
- **Security** — sign-in policy; separation-of-duties findings; departed accounts still
  active; stale service keys; expiring external grants; administration audit log
  (append-only)
- **Connected systems** — git, delivery tool, planning tool, finance, HR: what each owns,
  flow direction, what crosses, connection state
- **Settings** — tenant configuration, requirement key prefix, workflow thresholds,
  detector defaults

---

## 8. Requirement state machine

The lifecycle is a gated state machine, not a free-text status field. Illegal
transitions must be rejected by the service layer with a reason.

**D17 (2026-08-24): a full replacement, not an extension of D15/D16's five-state
machine.** Six states — `DRAFT`, `IN_REVIEW`, `REVIEWED`, `NEEDS_REVISION`, `APPROVED`,
`REJECTED` — and exactly eight legal edges, enforced as a single table
(`RequirementStatus.allowedNext()`), never scattered if/else checks. `NEEDS_REVISION` is
new: a decision maker can send a `REVIEWED` requirement back to its author instead of
approving or rejecting it, and a `REJECTED` requirement now reopens into
`NEEDS_REVISION` rather than `DRAFT` — a rejection already carries the reason the author
needs to act on. Rejecting directly from `IN_REVIEW` is gone: a decision is only made
once a review is actually complete. `APPROVED` is fully terminal — no transition leaves
it (D15's `APPROVED → REJECTED` and `APPROVED → IN_REVIEW` are both gone with it);
editing an approved requirement is refused exactly as before, through the existing
change-request path, which edits content directly and was never gated by this machine.
Forking a new version record when someone edits an `APPROVED` requirement is
deliberately deferred — `requirement.version` exists (always 1 today) reserved for it,
but the mechanism itself needs its own decision about how a fork relates to trace links,
design-diagram nodes and key uniqueness, and was not decided as a side effect of this one.

**Permission per edge, in one place.** "Author" is an identity check (`owner_id` or
`created_by`), not a grant — submitting, withdrawing and resubmitting are author-only.
"Reviewer" and "decision maker" reuse the existing `REVIEWER`/`APPROVER` roles (§4.4) at
the requirement's own scope — no new role. A decision maker may not decide on a
requirement they own or authored (SoD, the same principle already enforced when signing
a review round, VYB-0304, now also enforced on the transition itself) — this closes a
gap D15 explicitly left open, where the generic transition endpoint enforced no
role/SoD at all, for any edge. `RequirementTransitionAuthorizer` is the one place this
lives, shared by the single-transition endpoint and bulk edit, so the two cannot drift
the way the reason/blocking-clarification guards briefly did before VYB-0810.

```
                  ┌────────────────────────────────────────────────────┐
                  ▼                                                    │
  ┌───────┐  submit  ┌───────────┐  verify   ┌──────────┐  approve  ┌──────────┐
  │ DRAFT │─────────▶│ IN_REVIEW │──────────▶│ REVIEWED │──────────▶│ APPROVED │ (terminal)
  └───────┘           └───────────┘            └──────────┘            └──────────┘
      ▲                     │                       │
      │ withdraw            │                       │ send back / reject
      └─────────────────────┘                       ▼
                                          ┌──────────────────┐  reopen  ┌──────────┐
                                          │  NEEDS_REVISION   │◀────────│ REJECTED │
                                          └──────────────────┘          └──────────┘
                                                    │
                                                    │ resubmit
                                                    ▼
                                               IN_REVIEW
```

| Transition | Entry condition | Actor | Side effects |
|---|---|---|---|
| DRAFT → IN_REVIEW | Statement non-empty; ≥1 acceptance criterion **or** an explicit override with reason | Author | Creates review round; notifies participants |
| IN_REVIEW → DRAFT | — | Author (withdraw) | — |
| IN_REVIEW → REVIEWED | All required reviewers signed; no open blocking clarification | Reviewer on scope | The "Verify" action — Manual today (read the requirement on the Design screen, then confirm); AI-assisted is deferred (Rule 6), shown only as a disabled placeholder |
| REVIEWED → APPROVED | — | Decision maker (Approver) on scope, **not** the owner (SoD) | Step-up auth; signature recorded; revision frozen — this is the pipeline's terminal status |
| REVIEWED → REJECTED | Reason mandatory | Decision maker (Approver) on scope, **not** the owner (SoD) | Returns to author with the reason on the timeline |
| REVIEWED → NEEDS_REVISION | Reason mandatory | Decision maker (Approver) on scope, **not** the owner (SoD) | Returns to author with the reason on the timeline; `revision_count` increments |
| NEEDS_REVISION → IN_REVIEW | — | Author (resubmit, skips DRAFT) | — |
| REJECTED → NEEDS_REVISION | No fresh reason needed — the rejection already carries one | Decision maker (Approver) or Administrator | Reopens the requirement; `revision_count` increments |

**Bulk status change** must apply this machine per row and report skipped rows with the
reason, rather than moving everything and losing the evidence — the prototype's bulk edit
modal already states this behaviour.

---

## 9. API design

### 9.1 Conventions

- Base `/api/v1`. Resource-oriented, plural nouns, kebab-case paths.
- Pagination: `?page=0&size=50`, response envelope `{ content, page, size, totalElements, totalPages }`.
- Sorting: `?sort=status,asc&sort=priority,desc`.
- Filtering: `?filter=status:eq:APPROVED,priority:in:CRITICAL|HIGH`.
- Errors: **RFC 9457 Problem Details** (`application/problem+json`) with a stable `type`
  URI, plus a `violations[]` array for validation failures.
- Optimistic concurrency: `If-Match: "<revision>"` on mutating requests; `409` with both
  versions on mismatch.
- Idempotency: `Idempotency-Key` header honoured on all POSTs that create.
- Every response carries `X-Request-Id` for correlation with logs and audit events.

### 9.2 Principal endpoints

```
# Portfolio
GET    /products                      POST /products
GET    /products/{id}/apps            POST /apps
GET    /apps/{id}/capabilities        POST /capabilities
GET    /glossary                      POST /glossary

# Requirements
GET    /requirements                  ?filter=&sort=&page=      (the grid)
POST   /requirements
GET    /requirements/{id}
PATCH  /requirements/{id}             If-Match required
POST   /requirements/bulk             { ids[], patch{} }        → per-row result
GET    /requirements/{id}/revisions
GET    /requirements/{id}/history     lifecycle timeline
POST   /requirements/{id}/transition  { to, reason }            state machine
POST   /requirements/{id}/criteria
GET    /requirements/{id}/attachments POST (multipart)
POST   /requirements/{id}/comments
POST   /requirements/{id}/clarifications
POST   /requirements/{id}/defects
POST   /requirements/lint             { statement } → findings   (create-screen live lint)
POST   /requirements/similar          { statement } → matches    (duplicate check)

# Trace
POST   /trace-links                   DELETE /trace-links/{id}
GET    /trace/{type}/{id}/upstream    ?depth=
GET    /trace/{type}/{id}/downstream  ?depth=
GET    /trace/matrix                  ?appId=
GET    /trace/impact/{requirementId}  volume only

# Design
GET    /apps/{id}/flow                PUT /apps/{id}/flow
POST   /flows/{id}/nodes              PATCH/DELETE /nodes/{id}
POST   /flows/{id}/edges              DELETE /edges/{id}
GET    /apps/{id}/flow/coverage

# Detection
GET    /findings                      ?rule=&severity=&scope=&mine=
POST   /findings/{id}/accept          POST /findings/{id}/dismiss { reason }
POST   /detection/runs                trigger a sweep
GET    /detection/rules               PATCH /detection/rules/{key}

# Quality
GET/POST /reviews                     POST /reviews/{id}/sign
GET/POST /test-cases                  POST /test-runs
POST   /verifications                 (from CI)
GET/POST /defects

# Delivery
POST   /briefs                        { appId, capabilityIds[], target, developerId, options }
GET    /briefs/{id}                   GET /briefs/{id}/download
GET    /scope-signals                 ?scopeType=&scopeId=

# Releases
GET/POST /releases                    GET /releases/{id}/scope
POST   /baselines                     GET /baselines/{a}/diff/{b}
GET/POST /variants
GET    /environments                  POST /deployments
GET    /releases/{id}/notes

# Import
POST   /import-batches                (multipart upload)
GET    /import-batches/{id}/candidates
PATCH  /import-candidates/{id}
POST   /import-batches/{id}/commit    { candidateIds[] }

# Admin
GET/POST /users                       GET/POST/DELETE /access-grants
GET/POST /service-accounts            POST /service-accounts/{id}/rotate
GET    /audit-events                  ?from=&to=&actor=&action=
GET/PUT /integrations/{key}

# Webhooks (service-account authenticated)
POST   /webhooks/git                  commit trailers → trace links
POST   /webhooks/ci                   test results → verifications
```

### 9.3 Events

Publish domain events to an **outbox table** in the same transaction as the state change,
then relay. Never publish from inside the transaction directly.

`RequirementCreated`, `RequirementRevised`, `RequirementTransitioned`, `TraceLinkCreated`,
`TraceLinkInvalidated`, `VerificationRecorded`, `FindingOpened`, `FindingResolved`,
`BaselineFrozen`, `BriefGenerated`, `ClarificationRaised`, `ClarificationAnswered`.

Consumers: detection engine, notification service, audit writer, brief-staleness marker.

---

## 10. Frontend

### 10.1 Structure

```
src/
├── app/            router, providers, keycloak bootstrap, error boundary
├── shared/
│   ├── ui/         Btn Bdg Card Note Alert Empty Field Modal Seg StatRow Pips Av Bar Page
│   ├── grid/       DataGrid and all its behaviours
│   ├── api/        generated client, query keys, error mapping
│   └── tokens/     design tokens, both themes
└── features/
    ├── home/ mywork/ portfolio/ requirements/ design/
    └── analytics/ quality/ delivery/ releases/ admin/
```

Each feature folder owns its routes, components, hooks and types. Cross-feature imports
go through `shared` only — mirror the backend's module discipline and enforce it with
`eslint-plugin-boundaries`.

### 10.2 Rules

- **TypeScript strict.** Generate API types from the OpenAPI document; never hand-write
  a DTO.
- **TanStack Query** for all server state. No Redux. Local UI state is `useState`.
- **Optimistic updates with rollback** for grid edits and check-offs. Every mutation
  registers its inverse so the undo toast is real.
- Route-level code splitting; the grid and the design canvas are lazy.
- **Virtualise** the grid. Target 50,000 rows without degradation.
- Accessibility: keyboard navigation through the grid, focus traps in modals, visible
  focus rings, `aria-live` for toasts, colour never the sole signal — the coverage pips
  carry letters `U D C T` precisely for this reason.
- The prototype's CSS is the visual specification. Port the tokens verbatim.

### 10.3 Auth in the SPA

`oidc-client-ts` with Authorization Code + PKCE. Access token in memory only —
**never `localStorage`**. Refresh via silent renew in a hidden iframe or refresh-token
rotation. On `401` with `insufficient_user_authentication`, trigger step-up and retry the
original request once.

---

## 11. SDLC

Two distinct things share this name. Both are specified.

### 11.1 The SDLC that Vyoog implements (the product's own flow)

This is the process the tool enforces for its customers.

```
 ┌─────────┐   ┌─────────┐   ┌──────────┐   ┌─────────┐   ┌────────┐   ┌────────┐
 │ INTAKE  │──▶│ AUTHOR  │──▶│  REVIEW  │──▶│ APPROVE │──▶│  BUILD │──▶│ VERIFY │──▶ RELEASE
 └─────────┘   └─────────┘   └──────────┘   └─────────┘   └────────┘   └────────┘
      │             │              │              │             │            │
   Import      New req         Review        Signature      Brief +      Test run
   queue       screen          round         (step-up)      developer    → verification
      │             │              │              │             │            │
      └─────────────┴──────────────┴──────────────┴─────────────┴────────────┘
                    every stage writes to the accountability timeline
                    every stage re-runs detection on the affected subgraph
```

| Stage | Entry | Who | Exit | Gate |
|---|---|---|---|---|
| Intake | A document or a stakeholder request | BA | Candidates accepted into the register | Nothing enters without explicit acceptance |
| Author | Candidate or new requirement | BA, Architect | Statement + ≥1 criterion + upstream link | Live lint; gap preview |
| Review | Submitted | Reviewers, Compliance | All signatures present | No open blocking clarification |
| Approve | Reviewed | Approver on scope | Signed | **SoD: not the owner.** Step-up auth |
| Build | Approved | Developer named on the brief | Commit trailers link code to the requirement | Untraced commits raise a finding |
| Verify | Code present | QA | Test passes **at the current revision** | Revision bump invalidates automatically. D16: this evidence (`is_verified`) is tracked for quality reporting, but does not gate Release below — `requirement.status` has no `VERIFIED` value to reach |
| Release | **Approved** (D16: the requirement lifecycle's terminal status — not "Verified") | Release manager | In a frozen baseline, deployed | Anything not Approved is listed separately, never dropped |

**Defect loop.** A defect found in build, verify or production names the requirement it
traces to and classifies the root cause as *requirement defect* or *coding error*. This
is the feedback that measures whether requirement quality is improving.

**Change loop.** Any change to an approved requirement is a change request with computed
impact volume, board approval, and automatic suspect-marking downstream.

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

## 12. Build sequence

Do these in order. Each phase ends with something demonstrable.

### Phase 0 — Foundation (3–4 sessions)

Repository, **Maven multi-module skeleton per §2.4**, Docker Compose (Postgres +
Keycloak + Redis + MinIO), module packages, ArchUnit rules, Flyway baseline, **tenancy
plumbing and the isolation test**, Keycloak realm import, JWT validation, `app_user`
upsert, React + Vite skeleton, design tokens, shell with sidebar and routing, OIDC login.

*Exit:* log in with Keycloak, land on an empty Home, and the tenant-isolation test is
green. **Do not proceed until that test passes.**

### Phase 1 — The register (6–8 sessions)

Portfolio CRUD. Requirement CRUD with revisions and the state machine. Acceptance
criteria. The data grid with every behaviour in §7.4.1. Detail panel with concurrent-edit
protection. New-requirement screen with live lint. Trace links plus the closure table.
**Detectors 1, 3, 4, 5, 6 and the `ambig` rule.** Coverage pips.

*Exit:* author a requirement, link it, see real gaps appear.

### Phase 2 — Flow and evidence (6–8 sessions)

Review rounds with signature and step-up. Approval with SoD enforcement. Test cases,
CI ingest, verification bound to revision, stale-evidence detection. Defects with root
cause. My Work derived tasks. Clarification object. Git webhook and detector 8.

*Exit:* a requirement travels author → review → approve → verify with full accountability.

### Phase 3 — Delivery and releases (6–8 sessions)

Implementation briefs with all three targets, developer assignment, persistence and
staleness. Scope signals. Impact analysis. Baselines and diff. Releases, variants,
environments, deployments, release notes. Design module with the flow canvas.

*Exit:* generate a brief, hand it to Claude Code, freeze a baseline.

### Phase 4 — Intelligence (5–7 sessions)

pgvector, embedding pipeline. Detectors 7, 9, 10, 11, 12. Analytics module complete with
rules tuning and dismissal tracking. Import queue with parsing and candidate flags.

*Exit:* duplicate and conflict detection across apps, with measured false-positive rates.

### Phase 5 — Administration and hardening (5–7 sessions)

Full Administration module. Access grants UI. Service accounts and rotation. Security
findings. Connected systems. Audit log UI. Tenant export. Performance work. Accessibility
audit. Load testing.

*Exit:* production-ready.

### The four decisions still open

These block nothing in Phase 0 but must be answered before the phase in brackets.

| # | Decision | Recommendation |
|---|---|---|
| **P1** | Where do requirements live today, and what does migration import? | Build a ReqIF + Excel importer in Phase 1; it is also the onboarding path for every future customer |
| **P2** | Do sprints originate in Vyoog or in Jira? | **Jira.** Vyoog owns requirements; the delivery tool owns sprints. Sync one way. [Phase 3] |
| **P7** | Ship v1 with AI or without? | **Without.** GRAPH + RULE only. Seven detectors, no model, no inference cost, no false-positive problem on day one. |
| **P8** | Which modules in v1? | Home, Requirements, Analytics, Quality, Delivery. Design, Releases, My Work and Administration follow. |

---

## 13. Explicitly out of scope

Recording these prevents them being rebuilt by accident.

- **Cost, budget, initiatives, spend attribution.** Built during design, then removed.
  Finance owns money. Two documents in the project archive describe the removed
  Governance screen and are superseded.
- **Effort estimation.** Vyoog publishes exact scope signals; converting them to days is
  the delivery tool's job. No combined predicted-effort figure.
- **Gantt charts, resource levelling, capacity planning.**
- **Per-person productivity metrics**, velocity, hours. Team load is shown as *work
  state*, never as a ranking.
- **Sprint management.** Jira owns it.
- **A separate graph database.**
- **Compliance clause register.** Removed with Governance. Detector 7 still exists in
  the taxonomy but has no dedicated screen — decide in Phase 4 whether it becomes an
  Analytics tab.

---

## 14. Open questions for the product owner

1. **Multi-tenant users** — can one person belong to more than one tenant? This changes
   the token and security design materially (§3.6 item 4). Answer before Phase 0.
2. **Requirement key scheme** — currently `VY-nnnn` sequential per tenant. Should it
   encode the capability (`VY-ATT-0042`)? Changing it after data exists is painful.
3. **Tenant addressing** — path, subdomain, or token-only? Affects Keycloak redirect URIs.
4. **Vyoog PMS relationship** — shared realm? Shared tenant table? Does the PMS own the
   Product record? See §3.6.
5. **Document import formats** required at launch: Word, ReqIF, Excel, PDF?
6. **Retention** — how long are revisions and audit events kept? Some regimes require
   eight years, which changes partitioning and archival.

---

*End of specification. Companion files: `vyoog-schema.sql`, `vyoog-claude-code-kickoff.md`.*
