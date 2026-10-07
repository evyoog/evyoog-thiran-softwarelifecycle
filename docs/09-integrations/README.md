# Integrations

What Vyoog talks to, today and planned. Requirements for the built ones are in [`../02-requirements/FRD/administration/requirement.md`](../02-requirements/FRD/administration/requirement.md) (Integrations), [`delivery`](../02-requirements/FRD/delivery/requirement.md) (briefs, signals) and [`releases`](../02-requirements/FRD/releases/requirement.md) (deployments).

| System | Direction | What it does | Where defined |
|---|---|---|---|
| Keycloak (shared `eVyoog` realm) | both | sign-in (PKCE for the web app; a confidential client for the username and password screen), token validation, cross-app SSO bridge | [`../08-architecture/security/`](../08-architecture/security/), `DECISIONS.md` D6, D7, D21 |
| CI systems | in | test runs, commits and deployments pushed by a registered service account with the `ci:ingest` scope (`/api/v1/ci/*`) | [`../06-api/api-requirements/api-design.md`](../06-api/api-requirements/api-design.md) |
| Webhooks (git, ci, hr, planning) | in | HMAC-signed deliveries recorded once each (`/api/v1/webhooks/*`) | same |
| Planning / delivery tool | out | briefs and scope signals pushed as signed payloads (the "planning" connection), through the connector framework since VYB-0916 | Delivery requirements, [`connector-framework.md`](connector-framework.md) |
| Object storage (MinIO / S3) | out | requirement attachments | [`../08-architecture/deployment/running-minio-locally.md`](../08-architecture/deployment/running-minio-locally.md) |
| OpenAI | out | optional advisory AI (off by default; AI proposes, a human decides), reached only through the model gateway with retries and a circuit breaker (VYB-0936) | `CLAUDE.md` rule 6, [`../08-architecture/backend-architecture/ai-model-gateway.md`](../08-architecture/backend-architecture/ai-model-gateway.md) |
| Connector framework | out | **built (VYB-0913)**: the generic engine every outbound connector will use: authentication, retries with backoff, idempotency key, sync log, health state. No connector uses it yet | [`connector-framework.md`](connector-framework.md) |
| Agile Planner, Macro Planner | both | **planned**: a Planner-specific connector (VYB-0914, VYB-0915, VYB-0917; the generic planning push already uses the framework, VYB-0916), inbound sync (S4) and hierarchy sync (S7) | D24 (open), [`phase-6-planned.md`](../02-requirements/functional-requirements/phase-6-planned.md) |

The Agile Planner API or event contract and a test instance are still needed before the Planner-specific rows (VYB-0914 onward) can start (D24). VYB-0913 was built first because it is the generic part and needs neither. What the Planner's own repository actually says, and how it differs from the register, is in [`agile-planner-contract-analysis.md`](agile-planner-contract-analysis.md).

The Macro Planner hierarchy sync (VYB-0932 to VYB-0935) is blocked too: its repository has no Product, Application, Capability or Feature data or API. See [`macro-planner-hierarchy-analysis.md`](macro-planner-hierarchy-analysis.md).
