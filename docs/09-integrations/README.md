# Integrations

What Vyoog talks to, today and planned. Requirements for the built ones are in [`../02-requirements/FRD/administration/requirement.md`](../02-requirements/FRD/administration/requirement.md) (Integrations), [`delivery`](../02-requirements/FRD/delivery/requirement.md) (briefs, signals) and [`releases`](../02-requirements/FRD/releases/requirement.md) (deployments).

| System | Direction | What it does | Where defined |
|---|---|---|---|
| Keycloak (shared `eVyoog` realm) | both | sign-in (PKCE for the web app; a confidential client for the username and password screen), token validation, cross-app SSO bridge | [`../08-architecture/security/`](../08-architecture/security/), `DECISIONS.md` D6, D7, D21 |
| CI systems, git | in | test runs, commits and deployments pushed by a registered service account with the `ci:ingest` scope (`/api/v1/ci/*`); the `git` and `ci` connections show when data last arrived, and the `git` connection's repository address makes commit links (VYB-0922) | [`ci-and-git.md`](ci-and-git.md), [`../06-api/api-requirements/api-design.md`](../06-api/api-requirements/api-design.md) |
| Webhooks (git, ci, hr, planning) | in | HMAC-signed deliveries recorded once each (`/api/v1/webhooks/*`) | same |
| Planning / delivery tool | out | briefs and scope signals pushed as signed payloads (the "planning" connection), through the connector framework since VYB-0916 | Delivery requirements, [`connector-framework.md`](connector-framework.md) |
| Object storage (MinIO / S3) | out | requirement attachments | [`../08-architecture/deployment/running-minio-locally.md`](../08-architecture/deployment/running-minio-locally.md) |
| OpenAI | out | optional advisory AI (off by default; AI proposes, a human decides), reached only through the model gateway with retries and a circuit breaker (VYB-0936) | `CLAUDE.md` rule 6, [`../08-architecture/backend-architecture/ai-model-gateway.md`](../08-architecture/backend-architecture/ai-model-gateway.md) |
| Connector framework | out | **built (VYB-0913)**: the generic engine every outbound connector uses: authentication, retries with backoff, idempotency key, sync log, health state. The planning push and the Agile Planner webhooks use it | [`connector-framework.md`](connector-framework.md) |
| Agile Planner | both | **built, never run against the real Planner** (D24, D50 to D52): receives its Functions as draft requirements, holds later changes as proposals, tells it back with four signed webhooks | [`agile-planner-requirements-intake.md`](agile-planner-requirements-intake.md), [`agile-planner-webhooks.md`](agile-planner-webhooks.md), [`agile-planner-field-ownership.md`](agile-planner-field-ownership.md) |
| Macro Planner | in | **planned and blocked**: hierarchy sync (VYB-0932 to VYB-0935) | [`macro-planner-hierarchy-analysis.md`](macro-planner-hierarchy-analysis.md) |

The Agile Planner API or event contract and a test instance are still needed before the Planner-specific rows (VYB-0914 onward) can start (D24). VYB-0913 was built first because it is the generic part and needs neither. What the Planner's own repository actually says, and how it differs from the register, is in [`agile-planner-contract-analysis.md`](agile-planner-contract-analysis.md).

The Macro Planner hierarchy sync (VYB-0932 to VYB-0935) is blocked too: its repository has no Product, Application, Capability or Feature data or API. See [`macro-planner-hierarchy-analysis.md`](macro-planner-hierarchy-analysis.md).
