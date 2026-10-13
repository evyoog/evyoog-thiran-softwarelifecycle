# Pilot rehearsals: load, restore and point-in-time recovery (VYB-0959)

**Performed:** 2026-10-13, session 108, on one sandbox machine (4 cores, 16 GB, PostgreSQL 16.13 with `shared_buffers` 128 MB, the real
application on a real port, the load generator on the same machine). Every figure below was measured in that run unless it says
**extrapolated**. This page **supersedes** [`load-test-rehearsal.md`](load-test-rehearsal.md) (VYB-0780/0781, 2026-08-09: the grid query
only, not through HTTP) and [`backup-restore-rehearsal.md`](backup-restore-rehearsal.md) (VYB-0785, 2026-08-09, schema of that day);
those stay as the record of what was done then.

The onboarding half of the row is [`pilot-onboarding.md`](pilot-onboarding.md) and the test that runs it, `PilotDryRunIT`.

## What is and is not representative

| It is | It is not |
|---|---|
| The real application, every filter, the real security configuration, real signed bearer tokens (a throwaway key stands in for Keycloak and goes through the production token validator) | Keycloak: no sign-in redirect, no token refresh, no network |
| Real PostgreSQL 16 with pgvector and the whole schema (57 migrations) | The production database server: no RDS, no network between application and database, a small cache |
| Concurrent virtual people with think time, reading and writing through HTTP | A real team: the mix of actions is mine, from how the screens call the API |
| 50,000 requirements with criteria and embeddings | **Real embeddings**: the seeded vectors are synthetic and very similar to each other, so duplicate detection finds far more candidates than real text would. Treat the write-path numbers at 50,000 as pessimistic on matches and unproven on real text |

The harness is `LoadRehearsalHttpRunner` and `rehearsal/RehearsalTokens`/`LoadStats` (named so Surefire and Failsafe never pick it up;
`LocalDatabase` refuses a non-local database). The two recovery scripts are in `deployment/rehearsal/`.

## Targets (mine; change them if you disagree)

The register gave none. I stated these before running, so the results below are pass or fail, not just numbers.

| For | Target |
|---|---|
| Pilot-sized (2,000 requirements), 15 people with 1 to 3 s of think time | no errors; reads p95 at most 500 ms; writes p95 at most 1,000 ms |
| 50,000 requirements, the same 15 people | no errors; reads p95 at most 1,000 ms; writes p95 at most 2,000 ms |
| The nightly detection sweep | finishes within an hour, at any size the pilot could reach |
| Restore from a dump, 500 MB | back and serving within 15 minutes |
| Point-in-time recovery | to a chosen second, with a stated bound on what is lost if the primary is destroyed |

## Load results

### Pilot-sized: 2,000 requirements (after the fix below)

| Phase | Requests | Errors | Reads p50 / p95 / p99 | Writes p50 / p95 / p99 |
|---|---|---|---|---|
| Realistic: 15 people, 62 s, 8.2 req/s | 510 | **0** | 14 / 41 / 432 ms | 66 / 139 / 192 ms |
| Stress: 60 people with 50 to 200 ms think time, 30 s, 283 req/s | 8,609 | **5** | 60 / 175 / 305 ms | 280 / 1,032 / 2,034 ms |

**Pass** on the realistic target. The stress phase is not a pilot load (60 people clicking every 100 ms); it is there to find where it
breaks. It broke: five writes returned 500 (finding 2 below).

### 50,000 requirements

| Phase | Requests | Errors | Reads p50 / p95 / p99 | Writes p50 / p95 / p99 |
|---|---|---|---|---|
| Realistic: 15 people, 62 s, 8.1 req/s | 502 | **0** | 25 / 224 / 651 ms | 486 / 660 / 695 ms |
| Stress: 60 people, 32 s, 72 req/s | 2,289 | **0** | 507 / 1,207 / 1,581 ms | 1,674 / 3,900 / 4,913 ms |

**Pass** on the realistic target. In the stress phase reads were already at about a second and writes at four.

### The nightly sweep

| Size | Result |
|---|---|
| 2,000 requirements | 29.1 s |
| 50,000 requirements | **did not finish within 20 minutes**; stopped. **Extrapolated** (the work is the square of the row count): about 5 hours |

**Fail** against the one-hour target at 50,000. The sweep's duplicate scan compares every embedding with every other (two such queries were running when it was stopped); the 02:00 job would
still be running when the team arrives. At pilot size it is no problem.

## A defect the rehearsal found and fixed here

`DuplicateAcrossApplicationsDetector.scanOne`, which runs after **every write** to a requirement, did not scan that requirement: it ran
the whole cross-application duplicate comparison (every embedding against every other) and then kept the pairs that included the
requirement. That cost about 1.2 s per write at 2,000 requirements and grew with the square of the count. It now compares only the
requirement written, from either side of the pair, and returns the same pairs (`DuplicateScanOneIT`; each side removed in turn fails it).

| 2,000 requirements | Before | After |
|---|---|---|
| Writes p50 / p95, realistic phase | 686 / 1,568 ms | 66 / 139 ms |
| Throughput in the stress phase | 50 req/s | 283 req/s |
| Sweep | 34.3 s | 29.1 s |

The cost per write is now linear in the number of embeddings (66 ms at 2,000, 486 ms at 50,000). One scan of one requirement against
50,000 took about 200 ms in `psql` on this data. The HNSW index on the embeddings is not used by that join form.

## Findings that are not fixed here (each is its own change)

1. **The nightly sweep is quadratic and does not finish at 50,000.** Fix needs a decision on how the sweep should find similar pairs (an index lookup per requirement, or only what changed since the last sweep).
2. **Concurrent writes can fail with a 500.** Under the stress phase, two writes rescanning the same shared finding deadlock; the detection step catches the error and carries on, but the database has already aborted the write's transaction, so the write then fails with "current transaction is aborted" (`SQLSTATE 25P02`, seen on the brief-staleness update). A person at pilot load will not meet it often (none in the 510 realistic-phase requests; 5 in the 8,609 of the stress phase), but it makes the write fail rather than skip the detection.
3. **`PATCH /requirements/{id}` without `capabilityId` removes the placement**, and with it the editor's rights from that placement. Clients must send it back every time.
4. **`POST /teams` returns nothing.** The caller must list and match by name.
5. **A write costs more as the data grows** (finding above): 486 ms at 50,000 against a 2 s target is fine for a pilot, but it is the same linear cost as the sweep's quadratic one.
6. A person who has never signed in can be granted a role only through `POST /users`, and only a typed email links the row to the real person later; nothing checks it against Keycloak (`PilotDryRunIT` shows the mismatch gives a second, empty account).

## Backup and restore, on today's schema

`deployment/rehearsal/backup-restore.sh vygload50`: a custom-format dump of the 50,000-requirement database (510 MB), restored into a new
empty database, then compared.

| Step | Result |
|---|---|
| Dump (`pg_dump -Fc`) | 6.0 s, 15 MB |
| Restore (`pg_restore -j 4`) | 48.9 s, no errors |
| Row counts of all 99 tables, extensions (4), Flyway history (57 migrations) | **identical** |
| The application booted against the restored copy | up in 15.5 s; Flyway validated 57 migrations and found nothing to do; `/actuator/health` UP; `/api/v1/requirements` answered 401 without a token; no ERROR in the log |

**Pass** (15 minutes was the target). The dump is a small fraction of the database size (the vectors compress and indexes are stored as definitions, not data); I did not
measure where the restore's 49 s went, but the index rebuild is the likely bulk.

## Point-in-time recovery

`deployment/rehearsal/pitr.sh vygload50`: a throwaway cluster with WAL archiving on (`archive_mode`, `archive_command` to a directory,
`archive_timeout` 60 s), the 50,000-requirement database loaded into it, a base backup (539 MB, 4.4 s), then known writes and a
recorded moment **T**, then a "mistake" (every approved requirement deleted, 19,100 rows, then 50 more writes), then the cluster
destroyed and rebuilt from the base backup and archived WAL only.

| Check | Result |
|---|---|
| Recover to T (`recovery_target_time`, promote) | ready and writable in **2.0 s**; 50,197 requirements and 19,100 approved, exactly as at T; every table's row count equal to T; nothing written after T present |
| Recover to the end of the archive after destroying the primary | ready in 4.5 s; the 50 writes in an archived segment came back; **the one write committed after the last archived segment was lost** |

So the recovery point you actually get is the last archived segment: **at most about `archive_timeout` (60 s) plus the archiver's own
delay**, if the primary and its own WAL are both lost. Replay was fast because the test wrote little after the base backup; a real day of
writes replays at the rate the WAL was written, so the time grows with the time since the last base backup.

**What this does not test:** the production database. The company's database is a shared RDS instance, where backup, point-in-time
recovery and snapshots are RDS's own features and not this mechanism. Rehearse that with the operator on a copy (restore a snapshot to a
new instance, restore to a point in time, run the application against it) before the pilot; this page shows only that the schema, the
extensions and the application survive a restore and that PostgreSQL's own mechanism works on this data.

## Not rehearsed (written up, not tested)

- **Attachments (the object store).** Files live in S3/MinIO, not in the database. A database restore brings back the rows that point at them and nothing else; the bucket needs its own versioning or replication, and a restore must be matched to a bucket state no older than the database's. Not rehearsed: there is no MinIO or S3 in this sandbox.
- **Keycloak.** Identity is the shared `eVyoog` realm, owned outside Vyoog. A Vyoog restore does not restore users or sessions, and a restored database must meet the same realm. Vyoog keys people by the token's subject and claims a prepared row by email; losing and recreating Keycloak users with new subjects would orphan the grants in the restored database. Not rehearsed: no Keycloak here.
- **Redis, the front end and the secrets** were not part of this.
- A restore into **the same instance, over the live database**, was not done (the script never does).
