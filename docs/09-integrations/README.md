# Integrations

What Vyoog talks to, today and planned. Requirements for the built ones are in [`../02-requirements/FRD/administration/requirement.md`](../02-requirements/FRD/administration/requirement.md) (Integrations), [`delivery`](../02-requirements/FRD/delivery/requirement.md) (briefs, signals) and [`releases`](../02-requirements/FRD/releases/requirement.md) (deployments).

| System | Direction | What it does | Where defined |
|---|---|---|---|
| Keycloak (shared `eVyoog` realm) | both | sign-in (PKCE for the web app; a confidential client for the username and password screen), token validation, cross-app SSO bridge | [`../08-architecture/security/`](../08-architecture/security/), `DECISIONS.md` D6, D7, D21 |
| CI systems | in | test runs, commits and deployments pushed by a registered service account with the `ci:ingest` scope (`/api/v1/ci/*`) | [`../06-api/api-requirements/api-design.md`](../06-api/api-requirements/api-design.md) |
| Webhooks (git, ci, hr, planning) | in | HMAC-signed deliveries recorded once each (`/api/v1/webhooks/*`) | same |
| Planning / delivery tool | out | briefs and scope signals pushed as signed payloads (the "planning" connection) | Delivery requirements |
| Object storage (MinIO / S3) | out | requirement attachments | [`../08-architecture/deployment/running-minio-locally.md`](../08-architecture/deployment/running-minio-locally.md) |
| OpenAI | out | optional advisory AI (off by default; AI proposes, a human decides) | `CLAUDE.md` rule 6 |
| Connector framework | out | **built (VYB-0913)**: the generic engine every outbound connector will use: authentication, retries with backoff, idempotency key, sync log, health state. No connector uses it yet | [`connector-framework.md`](connector-framework.md) |
| Agile Planner, Macro Planner | both | **planned**: a connector replacing the generic planning push (VYB-0914 to VYB-0917), inbound sync (S4) and hierarchy sync (S7) | D24 (open), [`phase-6-planned.md`](../02-requirements/functional-requirements/phase-6-planned.md) |

The Agile Planner API or event contract and a test instance are still needed before the Planner-specific rows (VYB-0914 onward) can start (D24). VYB-0913 was built first because it is the generic part and needs neither.
