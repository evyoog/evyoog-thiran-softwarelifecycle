# Access rules for write endpoints

Every write endpoint (POST, PUT, PATCH, DELETE) states who may call it. Added by VYB-0906; in progress (see "Status"): 60 of 88 endpoints done.

## How it works

1. The rule is declared on the handler: `@RequiresAccess(AccessRule.X)` (`backend/vyoog-api/.../config/RequiresAccess.java`).
2. `AccessInterceptor` enforces it **before** the request body is read, so a caller without the role gets a 403 whatever they send, and a handler cannot forget to call the guard.
3. `AccessRule` (`vyoog-domain`, `com.vyoog.identity`) is the roles matrix of [`keycloak-and-authorization.md`](keycloak-and-authorization.md) §4.4 as data:

| Rule | Roles that satisfy it | Matrix column |
|---|---|---|
| `PERSON` | any signed-in person | Read; also the caller's own state, a question or comment, advice that stores nothing, AI proposals |
| `CREATE_EDIT_REQ` | Business Analyst, Architect | Create req, Edit req |
| `REVIEW` | Reviewer, Approver, Compliance Lead, Architect | Review |
| `APPROVE` | Approver | Approve |
| `VERIFY` | Tester | Verify |
| `BASELINE` | Approver | Baseline |
| `ADMIN` | Administrator | Admin |

A platform **Administrator passes every rule**. A **service account**, or a token with neither a registered client nor an email claim, passes none of them.

## Where the role is checked

`RequiresAccess.scope`:

- `NONE`: platform level only (`PERSON`, `ADMIN`).
- `REQUIREMENT` / `CRITERION`: the id in the URL names a requirement (or an acceptance criterion, resolved to its requirement); the role must be held at that requirement's capability, application or product, or any scope above it. An unknown id is a 404.
- `BATCH` / `CANDIDATE` / `ANALYSIS`: the id in the URL names an import batch, one of its candidates, or a document analysis; the role must be held on the application the batch was uploaded to (or above it). An unknown id is a 404. Upload takes the application as a request parameter, so it is checked "somewhere" first and then on that application in the handler.
- `ANYWHERE`: the target is in the body, so the caller must hold the role at some scope; the handler adds a scoped check where one matters (creating a requirement checks the placement in the body).

## Adding or changing an endpoint

1. Annotate the handler.
2. Add it to `EXPECTED` in `AccessPolicyTest`. The rule is written out a second time on purpose, so changing it is a visible, reviewed change.
3. `AccessRulesTest` then checks it automatically for every role, an administrator, an ordinary user and a service account.

`AccessPolicyTest` fails the build for any write endpoint that is not annotated, not guarded in its own code, not listed as open by design (login, webhooks, internal SSO, which authenticate themselves another way), and not on the shrinking `PENDING` list.

## Status

VYB-0906 is split into three sessions (`BUILD-REGISTER.md`):

- **6a, done:** the mechanism, the tests, and the requirement-core endpoints (requirements, acceptance criteria, bulk edit, comments, clarifications, change requests, reviews, findings, trace links): 26 endpoints.
- **6b, done:** products, applications, capabilities and clauses (Administrator); glossary, documents, variants and all import steps (Business Analyst or Architect, import checked on the batch's application); the variant matrix (any signed-in person): 34 endpoints.
- **6c, pending:** design, releases, defects, test cases, briefs, environments, teams, tasks, notifications, saved views, lint, AI re-embed (28 endpoints).

Endpoints guarded in their own code (administration, requirement edit and delete, import commit and delete, team role changes, brief push, and others) are recognised by `AccessPolicyTest` by reading their source.
