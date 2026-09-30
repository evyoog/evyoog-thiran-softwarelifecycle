<!-- Split verbatim from the former backend/docs/vyoog-build-specification.md (lines 1192–1209). Section numbers are unchanged; docs/02-requirements/SPECIFICATION-INDEX.md maps every section to its file. -->

### 7.10 Administration

- **Users** — directory, source (SSO / HR system / local invite), MFA state, last seen,
  status, grants per user
- **Access grants** — role × scope, add/revoke, expiry mandatory for external users
  (maximum 180 days)
- **Roles** — the permission matrix (§4.4), read-only display of what each role may do
- **Service accounts** — scopes, last used, key age, rotate
- **Security** — sign-in policy; separation-of-duties findings; departed accounts still
  active; stale service keys; expiring external grants; administration audit log
  (append-only)
- **Connected systems** — git, delivery tool, planning tool, finance, HR: what each owns,
  flow direction, what crosses, connection state
- **Settings** — tenant configuration, requirement key prefix, workflow thresholds,
  detector defaults

---
