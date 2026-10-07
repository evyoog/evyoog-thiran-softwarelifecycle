# Access rules for write endpoints

Every write endpoint (POST, PUT, PATCH, DELETE) states who may call it. Added by VYB-0906 (complete). Every write endpoint is classified.

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

`AccessPolicyTest` fails the build for any write endpoint that is not annotated, not guarded in its own code, and not listed as open by design (login, refresh, logout, webhooks, internal SSO, which authenticate themselves another way). The `PENDING` list it used to allow is now empty.

## Status

VYB-0906 was done in three sessions (`BUILD-REGISTER.md`); all 88 write endpoints that needed a rule had one at the end of VYB-0906 (VYB-0923 added nine; see the last bullet):

- **6a, done:** the mechanism, the tests, and the requirement-core endpoints (requirements, acceptance criteria, bulk edit, comments, clarifications, change requests, reviews, findings, trace links): 26 endpoints.
- **6b, done:** products, applications, capabilities and clauses (Administrator); glossary, documents, variants and all import steps (Business Analyst or Architect, import checked on the batch's application); the variant matrix (any signed-in person): 34 endpoints.
- **6c, done:** design flows and brief generation (Business Analyst or Architect); releases (Approver); defects and test cases (Tester); environments, creating a team and AI re-embed (Administrator); adding a team member (Administrator or the team's lead, like role changes and removal); tasks, notifications, saved views and lint (any signed-in person, their own state): 28 endpoints.

- **VYB-0923, done:** test plans, suites, a suite's cases, a case's steps and run creation (9 write endpoints, `TestManagementController`), and VYB-0924a added four more on the same rule (start, record a step result, record a case result, complete a run), VYB-0924b three more (add step evidence, add case evidence, retest) and VYB-0926 two more (raise a defect from a step or a case) are `VERIFY` (Tester), anywhere. The matrix has no column for test planning; Verify is the nearest, the same rule test cases use. Reads are open to any signed-in person.
- **VYB-0928, done:** moving a release (`ReleaseLifecycleController#transition`) is `BASELINE` (Approver), anywhere, like every release write. Editing the readiness-gate configuration is administrator-only, guarded in the handler. Reads are open to any signed-in person.
- **VYB-0929, done:** the same transition endpoint additionally requires **step-up** (checked in the handler, before the domain runs) and a person, not a service account, when the target is FROZEN or RELEASED; opening and reopening do not.
- **VYB-0930, done:** committing several requirements at once (`ReleaseController#commitMany`) is `BASELINE` (Approver), anywhere, like committing one. The candidates, the committed list with titles and the notes export are reads, open to any signed-in person.
- **VYB-0931, done:** reopening, editing, assigning and linking a defect (`DefectController#reopen`, `#edit`, `#assign`, `#link`) are `VERIFY` (Tester), anywhere, like closing one. Marking a defect fixed (`#markFixed`) is guarded in the handler: its assigned developer, or a Tester, or an administrator. Commenting (`#addComment`) is any signed-in person, not a service account. Reads are open to any signed-in person.
- **VYB-0937, done:** changing which kinds of personal data redaction is switched off for (`SettingsController#setAiRedaction`, `PUT /api/v1/settings/ai-redaction`) is administrator-only, guarded in the handler like every other setting. Secrets cannot be switched off by any role. The setting is readable with the other settings (`GET /api/v1/settings`).
- **VYB-0938, done:** deciding an AI proposal (`AiProposalController#decide`) is guarded in the handler by the rule of the proposal's kind, at the proposal's requirement: a rewrite or a brief elaboration needs the rule for editing a requirement (`CREATE_EDIT_REQ`), a test case needs `VERIFY`; a service account is refused. Drafting brief elaborations (`BriefController#draftElaborations`) is `CREATE_EDIT_REQ`, anywhere, like generating a brief. The proposal reads are open to any signed-in person.

Endpoints guarded in their own code (administration, requirement edit and delete, import commit and delete, team role changes, brief push, and others) are recognised by `AccessPolicyTest` by reading their source.
