<!-- Split verbatim from the former backend/docs/vyoog-build-specification.md (lines 222–394). Section numbers are unchanged; docs/02-requirements/SPECIFICATION-INDEX.md maps every section to its file. -->

> **Superseded.** Vyoog is single-tenant (`docs/DECISIONS.md` D3). This is the original multi-tenant design, kept for the record; the code has no `tenant_id` and no row-level security.

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
