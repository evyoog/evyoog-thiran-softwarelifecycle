# Backup/restore rehearsal (VYB-0785)

**Performed:** 2026-08-09, session 14, against the real local PostgreSQL 16.4 instance
(self-built with `pgvector`/`pg_trgm`/`pgcrypto` this session — see `BUILD-REGISTER.md`
session 14). Every command below was actually run; every number is a real measurement
from that run, not an estimate.

## Why this exists

Every prior session recorded VYB-0785 as TODO with the same reason: no real Postgres in
the sandbox to rehearse against. That blocker is gone as of session 14. This is the
rehearsal, run once real infrastructure existed to run it against.

## What was rehearsed

A full logical backup and restore of the `sandbox` database (schema
`vyg_requirement`, 65 tables, all three extensions), simulating "the original is gone,
stand up a working replacement from the backup alone" — then proving the replacement is
not just SQL that applied cleanly, but a database the real application actually boots
against.

## Steps and real results

**1. Seed a real row.** The database had no application-created data (nothing has
signed in through real Keycloak yet in this environment), so one illustrative row was
inserted directly — `product (key='BACKUP-DRILL', name='Backup Rehearsal Product')` —
purely so the restore had a real, identifiable row to verify came back intact, UUID and
all.

**2. Take the backup.**
```
pg_dump -h localhost -p 5433 -U postgres -d sandbox -Fc \
  -f sandbox.dump
```
Result: **0.153s**, 145,958-byte custom-format dump.

**3. Simulate the disaster and create a fresh target.** A *new*, empty database
(`sandbox_restored`) — never touching the original — so the drill proves
restore-from-nothing, not restore-over-itself:
```
psql -U postgres -c "CREATE DATABASE sandbox_restored;"
```

**4. Restore into it.**
```
pg_restore -h localhost -p 5433 -U postgres -d sandbox_restored -Fc \
  sandbox.dump
```
Result: **12.83s**, zero errors, zero warnings.

(First attempt used `--create`, which tried to `CREATE DATABASE sandbox` — the
*original* name embedded in the dump header, not the target name passed via `-d` — and
collided with the live database. Documenting the mistake here since it's the one every
real runbook for this exact tool hits: `--create` recreates the source's own name: for
a same-instance side-by-side restore, pre-create the target and omit `--create`.)

**5. Verify the restore, not just the exit code.**

| Check | Original | Restored | Match |
|---|---|---|---|
| Tables in `vyg_requirement` | 65 | 65 | ✅ |
| Extensions installed | `pg_trgm`, `pgcrypto`, `vector`, `plpgsql` | same 4 | ✅ |
| Flyway history | 8 migrations, all `success=t` | identical 8 rows | ✅ |
| Seeded row | `id=83e14e71-…`, key=`BACKUP-DRILL` | same `id`, byte-identical | ✅ |

**6. The real proof: boot the actual application against the restored copy.**
A second instance of the real `vyoog-api` jar, pointed at `sandbox_restored` on
port 8081:
```
DB_URL=jdbc:postgresql://localhost:5433/sandbox_restored?currentSchema=vyg_requirement,public \
  java -jar vyoog-api-0.1.0-SNAPSHOT.jar --server.port=8081
```
Log evidence:
```
Successfully validated 8 migrations
Current version of schema "vyg_requirement": 008
Schema "vyg_requirement" is up to date. No migration necessary.
Started VyoogApplication in 9.749 seconds
```
`GET /actuator/health` → `{"status":"UP"}`. Flyway didn't just tolerate the restored
schema — it recognized it as **exactly** current, meaning nothing about the restore
process left the schema in a state Hibernate/Flyway would object to.

**7. Teardown.** The 8081 instance was stopped, `sandbox_restored` was dropped,
and the seeded `BACKUP-DRILL` row was deleted from the original — the live database was
left exactly as it was before the drill, with no residue.

## What this rehearsal does and doesn't cover

- **Covers**: schema + row data for the entire `vyg_requirement` schema, across a real
  restore into a fresh target, with the real application confirmed bootable against it.
- **Doesn't cover**: attachment binaries in the object store (MinIO/S3 — a separate
  concern from the Postgres dump; see VYB-0732's own note that binary payloads are
  never in the tenant export either), point-in-time recovery (this was a logical
  `pg_dump`, not WAL-based PITR), and restoring onto a *different* major Postgres
  version than the one that produced the dump.
- **Timing caveat**: the 0.153s dump / 12.83s restore reflect this session's dataset
  (one seeded row, no bulk data) — not a measurement of restore time at production
  scale. That's a distinct, still-open question from VYB-0780/0781 (load at scale),
  which this rehearsal doesn't answer.
