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
| Agile Planner, Macro Planner | both | **planned**: a connector framework replacing the generic planning push, and hierarchy sync (rows VYB-0913 to VYB-0935) | D24 (open), [`phase-6-planned.md`](../02-requirements/functional-requirements/phase-6-planned.md) |

The Agile Planner API or event contract and a test instance are still needed before those rows can start (D24).
