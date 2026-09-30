<!-- Split verbatim from the former backend/docs/vyoog-build-specification.md (lines 1639–1655). Section numbers are unchanged; docs/02-requirements/SPECIFICATION-INDEX.md maps every section to its file. -->

## 14. Open questions for the product owner

1. **Multi-tenant users** — can one person belong to more than one tenant? This changes
   the token and security design materially (§3.6 item 4). Answer before Phase 0.
2. **Requirement key scheme** — currently `VY-nnnn` sequential per tenant. Should it
   encode the capability (`VY-ATT-0042`)? Changing it after data exists is painful.
3. **Tenant addressing** — path, subdomain, or token-only? Affects Keycloak redirect URIs.
4. **Vyoog PMS relationship** — shared realm? Shared tenant table? Does the PMS own the
   Product record? See §3.6.
5. **Document import formats** required at launch: Word, ReqIF, Excel, PDF?
6. **Retention** — how long are revisions and audit events kept? Some regimes require
   eight years, which changes partitioning and archival.

---

*End of specification. Companion file: `docs/archive/vyoog-claude-code-kickoff.md`. The schema is the Flyway migrations in `database/migrations`.*
