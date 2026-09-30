<!-- Split verbatim from the former backend/docs/vyoog-build-specification.md (lines 1552–1618). Section numbers are unchanged; docs/02-requirements/SPECIFICATION-INDEX.md maps every section to its file. -->

> The live plan and status are `BUILD-REGISTER.md`; this is the original phase design.

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
