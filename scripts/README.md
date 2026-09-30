# scripts

| Script | What |
|---|---|
| `run-local.sh` | starts the API against the local docker-compose database; loads the repository-root `.env` and refuses a non-local `DB_URL` |
| `generate-requirements-docs.py` | regenerates the per-feature requirement pages and the automated-tests index from `BUILD-REGISTER.md`; `--check` fails if they are stale (CI runs it) |
| `approve-requirements.sh` | moves DRAFT requirements to APPROVED through the real API (never straight into the database) |
