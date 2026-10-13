# Pilot rehearsals: load, restore and point-in-time recovery (VYB-0959)

**Performed:** 2026-10-13, session 108, on one sandbox machine (4 cores, 16 GB, PostgreSQL 16.13 with `shared_buffers` 128 MB, the real
application on a real port, the load generator on the same machine). Every figure below was measured in that run unless it says
**extrapolated**. This page **supersedes** [`load-test-rehearsal.md`](load-test-rehearsal.md) (VYB-0780/0781, 2026-08-09: the grid query
only, not through HTTP) and [`backup-restore-rehearsal.md`](backup-restore-rehearsal.md) (VYB-0785, 2026-08-09, schema of that day);
those stay as the record of what was done then.

The onboarding half of the row is [`pilot-onboarding.md`](pilot-onboarding.md) and the test that runs it, `PilotDryRunIT`.

> **Corrected and extended 2026-10-13, session 110 (VYB-0960).** The 50,000-requirement figures first written here were measured on wrong data:
> the seeding gave **every requirement the same embedding** (a subquery that PostgreSQL evaluated once), so every requirement was a 100%
> duplicate of every other, the dump compressed to almost nothing, and the restore skipped the cost of building a vector index over real,
> different vectors. The seed is fixed (each requirement has its own random vector; 0.5% are made near-copies of one in another application so
> the duplicate rule has real pairs). **Every 50,000-row figure below is the re-measurement.** The restore result changed from a pass to a
> fail. The nightly sweep and the 500s under load, found by the first run, were fixed in VYB-0960 and are re-measured here.

## What is and is not representative

| It is | It is not |
|---|---|
| The real application, every filter, the real security configuration, real signed bearer tokens (a throwaway key stands in for Keycloak and goes through the production token validator) | Keycloak: no sign-in redirect, no token refresh, no network |
| Real PostgreSQL 16 with pgvector and the whole schema (57 migrations) | The production database server: no RDS, no network between application and database, a small cache |
| Concurrent virtual people with think time, reading and writing through HTTP | A real team: the mix of actions is mine, from how the screens call the API |
| 50,000 requirements with criteria and embeddings (1,536 numbers each, as stored) | **Real embeddings**: the vectors are random, so unrelated requirements look unrelated, as unrelated text does, but real text has clusters (a screen's requirements resemble each other) that random vectors lack. Duplicate pairs are planted (0.5% of requirements), not found in real wording. Treat the write-path and sweep numbers as unproven on real text |

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

Measured after the VYB-0960 fixes (the 5 failed writes in the first run's stress phase are the defect fixed there, described below).

### Pilot-sized: 2,000 requirements

| Phase | Requests | Errors | Reads p50 / p95 / p99 | Writes p50 / p95 / p99 |
|---|---|---|---|---|
| Realistic: 15 people, 62 s, 8.0 req/s | 499 | **0** | 16 / 40 / 586 ms | 69 / 131 / 151 ms |
| Stress: 60 people with 50 to 200 ms think time, 31 s, 260 req/s | 7,957 | **0** | 68 / 185 / 266 ms | 354 / 1,751 / 3,116 ms |

**Pass** on the realistic target. The stress phase is not a pilot load (60 people clicking every 100 ms); it is there to find where it breaks.
In the first run it broke (5 of 8,609 writes returned 500); it did not here.

### 50,000 requirements

| Phase | Requests | Errors | Reads p50 / p95 / p99 | Writes p50 / p95 / p99 |
|---|---|---|---|---|
| Realistic: 15 people, 63 s, 7.9 req/s | 496 | **0** | 27 / 207 / 698 ms | 517 / 626 / 815 ms |
| Stress: 60 people, 34 s, 61 req/s | 2,070 | **0** | 539 / 1,298 / 1,862 ms | 2,212 / 6,934 / 7,608 ms |

**Pass** on the realistic target. In the stress phase reads were already over a second and writes at seven.

### The nightly sweep

| Size | Before VYB-0960 | After VYB-0960 |
|---|---|---|
| 2,000 requirements | 29.1 s | first pass **41.6 s** (all 2,184 requirements checked; none left waiting); an ordinary night, **200 re-embedded, 8.7 s** |
| 50,000 requirements | did not finish in 20 minutes; **extrapolated** about 5 hours | first pass in a 5-minute budget: **715 requirements checked, 49,360 waiting** (about 0.56 s each); an ordinary night, **200 re-embedded, 254 s (4 min)** |

**Pass** on "finishes within an hour" for an ordinary night at 50,000 (4 minutes; the time is the 200 checks, about 0.56 s each, plus the one-query rules). **The first pass on a database that has never been swept is not an hour**: at 0.56 s each, 50,000 requirements is about **7.8 hours extrapolated**, which is eight nights of the 60-minute budget. That is by design (D48): it carries on each night, and a pilot starts at a few hundred requirements, where the first pass is under a minute. The nights it would take were not waited for: for the "ordinary night" line the position was moved to the end by hand, which is what they would do. At pilot size the new sweep is not faster than the old one (41.6 s against 29.1 s); the point is the scaling.

## Defects the rehearsal found and fixed

### In VYB-0959: a duplicate check on every write that scanned everything

`DuplicateAcrossApplicationsDetector.scanOne`, which runs after **every write** to a requirement, did not scan that requirement: it ran
the whole cross-application duplicate comparison (every embedding against every other) and then kept the pairs that included the
requirement. That cost about 1.2 s per write at 2,000 requirements and grew with the square of the count. It now compares only the
requirement written, from either side of the pair, and returns the same pairs (`DuplicateScanOneIT`; each side removed in turn fails it).
At 2,000 requirements writes went from p50 686 ms to 66 ms and the stress throughput from 50 to 283 requests a second. A write still scans the
requirement's embedding against the others (two index-free scans, about 0.35 s and 0.22 s in `psql` at 50,000), which is the **517 ms** write
above and grows with the count; the HNSW index on the embeddings is not used by that join form.

### In VYB-0960: the nightly sweep that did not finish

For the two rules that compare requirements with one another (duplicates; contradictions when AI is on) the sweep now checks only what
changed since the rule's last pass, from where it stopped, each requirement exactly as a write would, in a time budget; see
[`../../03-business-rules/detection-sweep.md`](../../03-business-rules/detection-sweep.md) and D48. Table above.

### In VYB-0960: concurrent writes failing with a 500

Under the stress phase, two writes re-checking requirements that share a finding took the locks on shared findings in different orders and the
database stopped one with "deadlock detected" (seen in PostgreSQL's own log: three-way cycles on `finding` rows). The detection step swallowed
the error, which left the write's transaction aborted, so the write failed with "current transaction is aborted" (`SQLSTATE 25P02`): a 500
that hid the cause. Findings are now locked and written one at a time in one fixed order, a database error during the check refuses the write with
its real cause, and one the database stops over a lock conflict answers **409 "Try again"**. Evidence: a test with 8 threads handing 10 shared
findings over in shuffled orders (`ConcurrentFindingReconcileIT`; without the ordering it fails with "deadlock detected"), and the stress phase above (0 of 7,957
and 0 of 2,070 failed; 5 of 8,609 before).

## Findings that are not fixed here (each is its own change)

1. **A write costs more as the data grows**: 517 ms at 50,000 against a 2 s target is fine for a pilot, but it is a linear scan on every write (above), and under stress at 50,000 writes reached seven seconds.
2. **`PATCH /requirements/{id}` without `capabilityId` removes the placement**, and with it the editor's rights from that placement. Clients must send it back every time.
3. **`POST /teams` returns nothing.** The caller must list and match by name.
4. A person who has never signed in can be granted a role only through `POST /users`, and only a typed email links the row to the real person later; nothing checks it against Keycloak (`PilotDryRunIT` shows the mismatch gives a second, empty account).
5. **A restore from a dump needs index-build memory (below).** Not a defect, but a setting a person doing a recovery must know.

## Backup and restore, on today's schema

`deployment/rehearsal/backup-restore.sh vygload50k`: a custom-format dump of the 50,000-requirement database (1,009 MB: 857 MB seeded, the rest
findings), restored into a new empty database, then compared. **Re-measured on the corrected data; the first run's 49 s restore was on identical
vectors and is withdrawn.**

| Step | Result |
|---|---|
| Dump (`pg_dump -Fc`) | 79.2 s, 365 MB |
| Restore, PostgreSQL's default index-build memory (`pg_restore -j 4`) | **1,217 s (20 minutes): over the 15-minute target. Fail.** |
| Restore with `maintenance_work_mem` 1 GB (`MAINTENANCE_WORK_MEM=1GB`) | **178.7 s (3 minutes). Pass.** About 7 times faster |
| Row counts of all 103 tables, extensions (4), Flyway history (60 rows, up to V059) | **identical** |
| The application booted against the restored copy | up in 16.3 s; Flyway validated and found nothing to do; `/actuator/health` UP; `/api/v1/requirements` answered 401 without a token; no ERROR in the log |

A restore rebuilds every index, and the vector (HNSW) index over 50,000 requirements dominates it; with the default 64 MB of build memory it spills.
**A recovery from a dump must set `maintenance_work_mem` (about 1 GB) for the restore**, or it takes about seven times longer. A physical restore
(a base backup with its log, below, or an RDS snapshot) does not rebuild indexes and takes seconds; it is the faster way back.

## Point-in-time recovery

`deployment/rehearsal/pitr.sh vygload50k`: a throwaway cluster with WAL archiving on (`archive_mode`, `archive_command` to a directory,
`archive_timeout` 60 s), the 50,000-requirement database loaded into it (270 s, with 1 GB of index-build memory), a base backup (972 MB, 7.4 s), then known writes and a
recorded moment **T**, then a "mistake" (every approved requirement deleted, 19,100 rows, then 50 more writes), then the cluster
destroyed and rebuilt from the base backup and archived WAL only.

| Check | Result |
|---|---|
| Recover to T (`recovery_target_time`, promote) | ready and writable in **9.1 s**; 50,175 requirements and 19,100 approved, exactly as at T; every table's row count equal to T; nothing written after T present |
| Recover to the end of the archive after destroying the primary | ready in 3.3 s; the 50 writes in an archived segment came back; **the one write committed after the last archived segment was lost** |

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
