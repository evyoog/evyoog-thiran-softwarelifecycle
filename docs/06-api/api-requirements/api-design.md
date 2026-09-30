<!-- Split verbatim from the former backend/docs/vyoog-build-specification.md (lines 1278–1385). Section numbers are unchanged; docs/02-requirements/SPECIFICATION-INDEX.md maps every section to its file. -->

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
