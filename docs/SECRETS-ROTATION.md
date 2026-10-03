# Secrets rotation and git history scrub (VYB-0900, D22)

**Status: the code no longer contains these secrets, but they are still valid and still in git history.** Removing them from `application.yml` does not make them safe. Treat every value below as public until it has been rotated. Rotation is done by a person with the right access, outside the code. This document is the checklist.

D22 (`docs/DECISIONS.md`) was **accepted on 2026-10-03** by the product owner; it reverses D9 and D20. Accepting it changes the policy only: nothing below is rotated or scrubbed until someone does it.

## 1. What to rotate

| # | Secret | Where it lives now | Owner (who rotates) | New value goes to |
|---|---|---|---|---|
| 1 | Database password for user `postgres` on the shared RDS instance (`vyg-batch-1…rds.amazonaws.com`, database `sandbox`) | was the `DB_PASSWORD` default in `application.yml` | Database administrator | `DB_PASSWORD` in the secrets manager, for Vyoog **and** every other app sharing that login (vyg-pms, the pricing tool) |
| 2 | Client secret of the `vyg-devops-ui` client, realm `eVyoog` | was the `KEYCLOAK_ROPC_CLIENT_SECRET` default | Keycloak administrator | `KEYCLOAK_ROPC_CLIENT_SECRET` |
| 3 | Client secret of the `eVyoog` client, realm `eVyoog` | was the `KEYCLOAK_IMPERSONATION_CLIENT_SECRET` default | Keycloak administrator | `KEYCLOAK_IMPERSONATION_CLIENT_SECRET`, **and** the same secret in vyg-pms, eis-platform and vyg-ticket, which share this client |
| 4 | SSO shared secret (`local-dev-only-shared-secret-change-me` was only the default; check what production actually uses) | `INTERNAL_SSO_SHARED_SECRET` | Whoever owns the SSO mesh | `INTERNAL_SSO_SHARED_SECRET` on **every** backend in the mesh at the same time |

Also check whether the same database password or `eVyoog` secret appears in other repositories (vyg-pms's `application.yml` is named in D9 and D20 as the pattern that was copied). Rotating here without rotating there leaves the secret exposed.

Not in scope but worth a look: `StorageConfig` still defaults the object-store keys to the local MinIO pair (`minio` / `minio123`). Make sure no deployed environment relies on that default.

## 2. Order of work

1. Confirm D22 is accepted.
2. Put the **new** values in the secrets manager for every environment first.
3. Rotate 1 to 4 (a Keycloak secret can be regenerated in the admin console under *Clients → Credentials*; the database password is a `ALTER ROLE` by the DBA). For 3 and 4, change all apps in the same window or SSO breaks between them.
4. Deploy this change (the app refuses to start without the six variables).
5. Verify: sign-in with username and password works, cross-app SSO works, and the old values are rejected (a login with the old `vyg-devops-ui` secret must fail).
6. Then scrub history (section 3). Do not scrub before rotating; the scrub is only tidying once the secrets are dead.

## 3. Rewriting git history (a person runs this, not Claude Code)

Rewriting history changes every commit id from the first affected commit onward. Everyone must re-clone afterwards, open pull requests must be re-created, and anything that pins a commit sha breaks. Only do it once rotation is confirmed, and announce it first.

The old values to remove are the ones that appeared in `application.yml`. Put them in a file **outside the repository** (never commit it), one per line, with `==>REMOVED` so `git filter-repo` replaces them:

```
# ../secrets-to-scrub.txt   (keep OUTSIDE the repo, delete after use)
<old database password>==>REMOVED
<old vyg-devops-ui client secret>==>REMOVED
<old eVyoog client secret>==>REMOVED
<old shared sso secret, if it was ever committed>==>REMOVED
```

The current values can be read from `git log -p -- backend/vyoog-api/src/main/resources/application.yml` (and `docs/DECISIONS.md` text mentioning them). Then, on a **fresh mirror clone**:

```bash
pip install git-filter-repo
git clone --mirror git@github.com:evyoog/evyoog-thiran-softwarelifecycle.git scrub.git
cd scrub.git
git filter-repo --replace-text ../secrets-to-scrub.txt

# Check nothing is left (run for each old value):
git log --all -p -S'<old value>' | head

git push --force --mirror
```

Afterwards:

- Ask GitHub Support to purge cached views and dangling commits, and check any forks and open pull request refs (`refs/pull/*` are not removed by a push).
- Everyone deletes their local clone and clones again.
- Run GitHub secret scanning on the repository to confirm it is clean.

If you skip the history rewrite, the secrets stay recoverable from any old clone. That is acceptable **only** because they were rotated in section 2.

## 4. Local development after this change

Nobody needs a real secret to develop locally. `cp .env.example .env`, keep the fake placeholders, and use the docker-compose database. Sign-in against the shared Keycloak realm needs the real `vyg-devops-ui` secret from a Keycloak administrator; without it everything except username/password sign-in still runs.
