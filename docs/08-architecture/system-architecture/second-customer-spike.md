# Spike: what a second customer would need

VYB-0954 (Phase 6, Sprint 11, F39). A written analysis, no code and no schema change. It is the input D25 waits for ("decide after the Sprint 11 spike"). It describes what the code assumes today, three ways to serve a second customer, what each needs, how big the work is, and how a move would go. **It recommends; the decision is the product owner's.** The finding text F39 is not in the repository, so the question is taken from D25 and the register row.

Sizes are the register's own: **S, M, L**, and **XL** for what no single row could hold. There are no hours and no money here (rule 7 is about the product, and the same discipline is kept): what each option adds to *run* is stated as things to keep running (an instance, a schema, a realm), not as a price.

## 1. What "a second customer" can mean

1. **A separate organisation with its own people, data and deployment**, nothing shared but the code. Typical when a customer needs isolation by contract.
2. **A separate organisation served from the same running application**, each seeing only its own data (multi-tenancy).
3. **A second business unit of the same organisation**, sharing people and identity. This needs no tenancy: it is a product or application in the portfolio (it already is), with grants at that scope.

Only 1 and 2 are in question. Nothing in the repository says which the product owner means, or what a customer would require for data location, identity or support; those answers change the choice (section 6).

## 2. What the code assumes today (D3: one customer, one schema)

| Where | What it assumes | Evidence |
|---|---|---|
| Database | One schema, `vyg_requirement`, on a shared instance; no `tenant_id`, no row-level security | D3; `application.yml` `flyway.schemas` and `default-schema`; the connection's `currentSchema` |
| Configuration | **Exactly one row** of settings: key prefix, stall thresholds, notification windows, the AI token budgets, the redaction switch, the bootstrap flag | `app_config` has `CHECK (id = 1)`; 43 references in 15 classes (`AppConfigService`, `AiBudgetService`, `RequirementKeyAllocator`, `TenantBootstrapService`, `SuspensionFilter`, ...) |
| Keys | One global sequence per kind: requirements, defects, change requests, test cases, test plans, risks | `*_key_seq` and the `*KeyAllocator` classes; a second customer would share the numbering |
| Reference data | Detector rule templates and other seeded rows exist once, from the migrations | `gap_rule_template` and its seeds; `TenantBootstrapService` states the rules are seeded, not created |
| Identity | One realm, `eVyoog`, shared with the company's other applications; the issuer is an environment variable | `application.yml` `issuer-uri` and `jwk-set-uri` (`KEYCLOAK_ISSUER`, `KEYCLOAK_JWKS`); `app_user.subject` is the Keycloak `sub` |
| First administrator | One bootstrap per deployment | `app_config.bootstrapped_at`, `TenantBootstrapService` |
| Background work | Scheduled jobs and a database lock that assume one data set | five `@Scheduled` jobs, `SchedulerLock`, the detection sweep |
| Export and reset | Whole-database operations | `TenantExportService` (every table, no matching import), `TenantHardResetService` |
| Integrations | Connections and secrets live in this schema | `integration_connection`, the connector framework |
| Size of the surface | 90 tables, 59 hard-coded `CHECK (... IN (...))` lists | the migrations |

Three things are already **per deployment**, which matters for the first option: the requirement key prefix, the Keycloak issuer, and every secret (D22: environment variables). An existing deployment layout has one set of settings per environment (`deployment/environments/dev`, `uat`, `prod`).

## 3. The options

### Option A: one deployment per customer ("silo")

Each customer gets its own copy: its own application instance, its own schema (or its own database), its own identity configuration. The code is unchanged.

- **Needs:** infrastructure for a repeatable new deployment (a schema, an instance, secrets, a realm or an identity provider connection, a first administrator via the bootstrap that exists); a runbook; a way to run each release's migrations against every customer's schema.
- **Code change:** none required. At most a small one (a configurable product name and logo).
- **Size:** **S to M** of work, once, plus an ongoing cost of **one more of everything to keep running** per customer: an instance to deploy and monitor, a schema to back up and migrate, a realm to administer.
- **Isolation:** the strongest: a bug cannot show one customer another's data, because they never share a process or a schema.
- **Upgrades:** every customer is upgraded, and every upgrade can fail, separately. This is the real cost, and it grows with the number of customers.
- **Fits:** a small number of customers (two to perhaps a handful), contractual isolation, different data locations.

### Option B: many customers in one running application, one schema each

One instance serves everyone; each request is routed to its customer's schema.

- **Needs:** resolving the customer on every request (from the token's issuer or a claim, or the host name); a routing data source and a pool per customer; migrations run per schema at start and at onboarding; **every piece of per-process state made per-customer**: the settings row (15 classes), the key sequences (already per schema, so fine), the scheduled jobs and their lock (each must run for every customer, in turn), the detection sweep, the AI budgets, caches and the notification registry, object storage paths, the connector framework; the token decoder accepting several issuers; per-customer onboarding and removal.
- **Code change:** wide but mostly mechanical. It touches the data source, the filters, the five scheduled jobs, the settings service and everything that reads it, and the tests (each integration test would need a second customer to prove isolation).
- **Size:** **L to XL** (more than one row), with the highest risk in the product: a request resolved to the wrong schema, or a thread or cache that outlives it, shows one customer's data to another. The original design (`multi-tenancy-superseded.md`) rejected schema-per-customer for this reason (the search path on pooled connections). It would need an architecture rule (ArchUnit) forbidding any data access outside the routing path.
- **Upgrades:** once, for everyone.
- **Fits:** many customers, or customers too small to justify a deployment each.

### Option C: one schema, a `tenant_id` on every table, row-level security

The design D3 removed.

- **Needs:** a `tenant_id` on 90 tables and every uniqueness rule made per tenant (keys, names), row-level security policies, a tenant context bound on every transaction, every query, entity and recursive query reviewed, the sequences made per tenant.
- **Size:** **XL**, and it would reverse D3, which was taken after reading how the company's other two applications work (each its own schema, no `tenant_id`).
- **Isolation:** enforced by the database, but one forgotten policy is a breach.
- **Not recommended:** the most work, the least consistent with the company's other applications, and no advantage over B at this scale.

### Side by side

| | A: deployment each | B: schema each, one app | C: tenant column + RLS |
|---|---|---|---|
| Work now | S to M | L to XL | XL |
| Code change | none | wide, mechanical | everything |
| Added to run, per customer | an instance, a schema, a realm | a schema, a realm | nothing |
| Upgrades | once per customer | once | once |
| Leak risk | none by construction | real; needs guards and tests | real; needs policies and tests |
| Reverses D3 | no | partly (one app, many schemas) | yes |
| Can start before a second customer exists | yes | no | no |

## 4. Moving to a second customer

**Option A.** The first customer is untouched. For the second: create the schema, deploy an instance with that schema and its own identity settings, run the migrations, bootstrap the first administrator, set the key prefix. The existing export is not needed.

**Option B from A, later.** A second customer on A can be moved into one application by exporting its schema and loading it behind the router. `TenantExportService` writes every table as rows with a manifest but **there is no import**, so that import (or a plain schema dump and restore) would be written then. No work today prepares for this, and none is needed: keeping every customer in its own schema (as under A) is what makes the move possible.

**Anything from today to C.** Every table gains a tenant and all existing rows are assigned to the first customer: a large forward-only migration against a populated database.

## 5. How this relates to VYB-0951 and VYB-0952

The artifact type registry and custom fields (the other halves of D25) **do not need tenancy**. Under A, each deployment has its own registry and fields, stored in its own schema, which is also how they would travel under B. The one thing to avoid is keeping such configuration in a single global row or in code: it must live in tables, so that it is per schema. D25 can therefore be decided in two parts: the artifact engine on its own merits, and the second customer separately.

## 6. Recommendation

**Choose A (a deployment per customer) when a second customer exists, and build nothing for tenancy before then.** It is the least work, the strongest isolation, needs no code change, keeps D3, and leaves B open: customers kept in separate schemas can be merged behind a router later if their number makes upgrading each one too costly. Revisit when any of these is true: more than about five customers; upgrades per customer become the main cost; a customer is too small to justify an instance.

Keep two habits so that nothing makes the move harder: new configuration goes in tables, not in the single settings row's new columns where it is avoidable; and nothing new assumes a global sequence or global seeded data without saying so.

## 7. What D25 needs from the product owner

1. **The artifact engine (VYB-0951 and VYB-0952): go ahead, or not yet?** If yes, its scope: which fields and types need to be configurable first.
2. **A second customer: which shape, and when?** A (recommended: nothing to build now), B (an L to XL project, to start when there is a customer to justify it), C (not recommended), or not now.
3. **What the first such customer would require:** its own identity provider, data location, contractual isolation, its own domain name. These decide between A and B and are not in the repository.

## 8. Limits of this spike

- No second customer, contract or requirement was available; the shape and its needs are assumptions listed above.
- The sizes are judgements from reading the code and the migrations, not measured; B in particular could be larger than L to XL once a real customer's identity and storage needs are known.
- Nothing was built or tried: no routing data source, no second schema was created.
- Infrastructure prices are not estimated (and the product holds none).
