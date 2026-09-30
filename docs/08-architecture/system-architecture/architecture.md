<!-- Split verbatim from the former backend/docs/vyoog-build-specification.md (lines 91–221). Section numbers are unchanged; docs/02-requirements/SPECIFICATION-INDEX.md maps every section to its file. -->

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

Full parent and module POMs are in **`docs/archive/vyoog-maven-project-setup.md`**.

Rules:

- Internal boundaries between the ten domain modules are enforced by **ArchUnit**, not by
  Maven. If a boundary needs extracting later, promote that package to its own module
  then — not speculatively now.
- `vyoog-domain` must not depend on `spring-boot-starter-web`. If a domain class needs
  an HTTP type, the design is wrong.
- Versions live only in the parent `dependencyManagement`. A child POM with a `<version>`
  on a managed dependency fails review.

---
