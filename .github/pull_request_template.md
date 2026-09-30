## What and why

Requirement(s): VYB-nnnn (one register row per pull request)

## Changes

- Code:
- Database migration (`database/migrations/Vnnn__...`), if any:
- Docs updated (`docs/`, and `BUILD-REGISTER.md`):
- Tests added or changed (named `VYBnnnn_ACn_...`):

## Checklist

- [ ] Every commit has a `Requirement: VYB-nnnn` trailer
- [ ] `cd backend && ./mvnw -B verify` passes; `cd frontend && npx tsc -b && npm test` passes
- [ ] Every new or changed write endpoint has an explicit role rule and a test that an unauthorised user gets 403
- [ ] Any schema change is a new forward-only migration
- [ ] No credential, key or token is committed; no new wildcard; no test reaches a non-local database
- [ ] `python3 scripts/generate-requirements-docs.py` run, if the register changed
- [ ] `BUILD-REGISTER.md` row updated
