<!-- Split verbatim from the former backend/docs/vyoog-build-specification.md (lines 395–532). Section numbers are unchanged; docs/02-requirements/SPECIFICATION-INDEX.md maps every section to its file. -->

> Note: where this section says `TENANT`, the code's platform-wide scope is `PLATFORM` (D3).

## 4. Keycloak and authorization

### 4.1 Division of responsibility

| Concern | Owner |
|---|---|
| Authentication, MFA, session, password policy, IdP federation | **Keycloak** |
| Coarse platform roles (`platform-admin`, `tenant-admin`, `user`, `service`) | **Keycloak** |
| Fine-grained authorization: role × scope | **Vyoog** (`access_grant` table) |

**Why fine-grained authorization is not in Keycloak.** Principle 9 requires grants like
"Business Analyst **on** Valam ▸ HR Intelligence". Expressing that in Keycloak means
either a role per scope (thousands of roles) or deep group paths, and either way it
lands in the access token and bloats every request. Scopes also change constantly — a new
capability should not require a Keycloak admin call. Vyoog owns this; Keycloak stays the
identity authority.

### 4.2 Realm topology

**Chosen: a single realm per environment, with one Identity Provider alias per customer
that federates their corporate SSO.** Home-IdP discovery by email domain routes the user.

Rejected alternative: realm-per-tenant. It gives stronger isolation and per-tenant
policy, but the issuer differs per tenant (complicating token validation), Keycloak
admin overhead grows linearly, and platform-wide administration becomes awkward. Revisit
only when a customer contractually demands an isolated realm — at which point that
tenant also moves to the isolated-database escape hatch in §3.1, and both changes ship
together.

```
realm: vyoog
├── clients
│   ├── vyoog-web        public,       Authorization Code + PKCE
│   ├── vyoog-api        bearer-only,  audience for access tokens
│   └── vyoog-service    confidential, client-credentials for integrations
├── realm roles
│   ├── platform-admin   cross-tenant, Vyoog staff only
│   ├── tenant-admin
│   ├── user
│   └── service-account
├── identity providers
│   ├── acme-saml        (customer A corporate SSO)
│   └── globex-oidc      (customer B)
└── client scopes
    └── vyoog-tenant     → adds the tenant claim (§4.3)
```

### 4.3 Token claims

Add a protocol mapper on the `vyoog-tenant` client scope producing:

```jsonc
{
  "sub": "8f14e45f-...",
  "email": "j.alvarez@acme.com",
  "preferred_username": "j.alvarez",
  "vyoog_tenant": "3b1f...",        // active tenant UUID
  "vyoog_tenants": ["3b1f...", "9a22..."],  // only if multi-tenant users are supported
  "realm_access": { "roles": ["user"] },
  "aud": "vyoog-api"
}
```

**Server-side rules, all mandatory:**

1. Validate `iss`, `aud`, `exp`, and the signature against JWKS. Cache JWKS; honour
   rotation.
2. Resolve `tenant_id` **from the token**, never from a request header, body or path
   parameter that the client controls — unless multi-tenant users are supported, in
   which case an `X-Vyoog-Tenant` header is accepted **only if it is a member of
   `vyoog_tenants`**, and rejected otherwise.
3. On first sight of a `sub`, upsert an `app_user` row (a local mirror; Keycloak stays
   the source of truth for identity attributes).
4. Bind `app.tenant_id` for the transaction (§3.3) before any repository call.

### 4.4 The role × scope model

```
grant = (user, role, scope_type, scope_id)

scope_type ∈ { TENANT, PRODUCT, APP, CAPABILITY, RELEASE }
```

A grant at `PRODUCT` implies the same role on every app and capability beneath it.
Resolution walks *up* the hierarchy from the target object and returns true on the first
matching grant.

**Roles and their permissions** (the matrix rendered on Administration → Roles):

| Role | Read | Create req | Edit req | Review | Approve | Verify | Baseline | Admin |
|---|---|---|---|---|---|---|---|---|
| Viewer | ✓ | | | | | | | |
| Business Analyst | ✓ | ✓ | ✓ | | | | | |
| Reviewer | ✓ | | | ✓ | | | | |
| Approver / Product Owner | ✓ | | | ✓ | ✓ | | ✓ | |
| Developer | ✓ | | | | | | | |
| QA / Tester | ✓ | | | | | ✓ | | |
| Compliance Lead | ✓ | | | ✓ | | | | |
| Architect | ✓ | ✓ | ✓ | ✓ | | | | |
| Administrator | ✓ | | | | | | | ✓ |

**Separation of duties**, enforced and surfaced on Administration → Security:

- A user may not approve a requirement they own or authored. Detect and block at the
  service layer; report existing violations as findings.
- Administrator combined with any sign-off role is a reportable finding.
- Service accounts may never approve, sign off, or administer. Enforce by rejecting
  those endpoints for any principal whose token has `service-account` in `realm_access`.

### 4.5 Step-up authentication

Approval and baseline freeze are signature events. Require a second factor **at the
moment of the action**, not merely at sign-in:

- Request the action with the current token.
- If `acr` is below the required level, return `401` with
  `WWW-Authenticate: Bearer error="insufficient_user_authentication", acr_values="silver"`.
- The SPA re-authenticates with `acr_values=silver` and retries.

Record the resulting `acr` and `auth_time` on the signature record.

### 4.6 Service accounts

Integrations authenticate with client credentials against `vyoog-service`. Each
integration gets its own client, its own secret, and the **narrowest scope that works**:

| Service account | Scopes |
|---|---|
| `git-webhook` | `link:write` |
| `ci-test-reporter` | `test:write` |
| `delivery-sync` | `req:read link:write status:read` |
| `export-job` | `req:read export` |

Key age is tracked and surfaced; rotation is a first-class endpoint. This is exactly what
Administration → Service accounts renders.

---
