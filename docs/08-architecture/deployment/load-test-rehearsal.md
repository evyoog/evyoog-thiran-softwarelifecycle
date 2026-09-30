# Load test rehearsal (VYB-0780/0781)

**Performed:** 2026-08-09, session 14, against the real local PostgreSQL 16.4 instance
and the real `vyoog-api` application (no mocks) — see `BUILD-REGISTER.md` session 14.
Every number below is a real measurement; the harness is
`backend/vyoog-api/src/test/java/com/vyoog/api/LoadRehearsalRunner.java`, run once by
explicit name (`-Dtest=LoadRehearsalRunner`) — it is not picked up by `mvn test` or
`mvn verify`, so this doesn't change what those commands do for anyone else.

## Why this exists

Every prior session recorded VYB-0780/0781 as TODO for the same reason: no real
Postgres to seed at scale or a real app to measure against. That blocker is gone as of
session 14. This is that measurement, run once real infrastructure existed.

## Setup

50,000 `requirement` rows, spread across 20 capabilities, all 5 statuses, all 8 types,
all 4 priorities, seeded directly via one set-based SQL `INSERT ... SELECT
generate_series(1, 50000)` (not one-row-at-a-time, and not through the API — there's no
real Keycloak session in this environment to author 50,000 requirements through the
UI/API path; see `BUILD-REGISTER.md`'s standing Keycloak disclosure). All seed rows
were removed after the rehearsal — the live database was left exactly as it was found.

## Result 1 — VYB-0131/0780: the grid query, real numbers

Calling the exact repository method `RequirementController#list` calls
(`RequirementRepository.findAll(spec, pageable)`), against the real 50,000-row table:

| Query | Time |
|---|---|
| Unfiltered, default sort, page 0 (size 50) | **73ms** |
| `status=IN_REVIEW` filter, page 0 | **57ms** |
| Sorted by `createdAt` desc, page 0 | **53ms** |
| Sorted by `createdAt` desc, **deep page** (page 900, ~row 45,000) | **77ms** |
| Trigram title search | **42ms** |

**All comfortably inside the 300ms target** from VYB-0131's original spec — the
`requirement_capability_id_status_idx` and `requirement_statement_idx` (GIN/trigram)
indexes already in `V001__baseline.sql` are doing real work here, not sitting unused.
This closes the *query-layer* half of VYB-0131/0780's "unmeasured performance target"
gap. It does **not** close the client-side half (virtualization, 50k-row DOM cost) —
that's a separate, still-open frontend gap (see the grid contract's own PARTIAL note in
`BUILD-REGISTER.md`), unaffected by this measurement.

## Result 2 — VYB-0781: the detection sweep, and a real bug it found

This did **not** go as cleanly, and that's the actually useful part of a load test.

Triggering a real, full `DetectionSweepService` sweep (the same method the "Run sweep"
button and the nightly cron call) against the 50,000-row dataset: the sweep did not
complete. It was killed after **8 minutes** of 100%+ CPU with the JVM never blocked on
the database (`pg_stat_activity` showed zero active queries throughout — this is a
compute problem, not a slow-query problem).

**Root cause, confirmed by a thread dump and a heap histogram of the actual stuck
process, not guessed at:**

```
"main" ... RUNNABLE
  at org.hibernate.persister.entity.AbstractEntityPersister.getPropertyValues
  at org.hibernate.event.internal.DefaultFlushEntityEventListener.flushEntities
  at org.hibernate.event.internal.AbstractFlushingEventListener.flushEverythingToExecutions
  at org.hibernate.internal.SessionImpl.autoFlushIfRequired
```
```
com.vyoog.detection.Candidate     50,000 instances
com.vyoog.detection.Finding       34,205 instances (and climbing)
```

`FindingReconciler.reconcile(String ruleKey, List<Candidate> candidates)`
(`backend/vyoog-domain/src/main/java/com/vyoog/detection/FindingReconciler.java:30-31`)
is `@Transactional` **once per detector, over that detector's entire candidate list for
the whole sweep** — for a detector whose candidates cover most of the table (this
seed's bare-bones requirements, with no acceptance criteria and no design/trace links,
trip several completeness detectors near-universally), that's tens of thousands of
entities loaded and persisted inside **one Hibernate session that is never cleared**.
Every subsequent auto-flush has to dirty-check everything the session has accumulated
so far — cost per row grows with how many rows came before it, so total cost is
quadratic in candidate count, not linear.

**This is a real, dataset-independent scalability defect, not an artifact of this
particular seed.** The seed being unrealistically bare (nothing has acceptance
criteria or design links) inflated *how many* candidates one detector produced, which
is why it surfaced at 50k rows rather than 5k — but the underlying bug (one long session
for an entire detector's candidate list) would degrade the same way on any dataset large
enough to accumulate enough entities in one uncommitted session. Nothing about this was
simulated or estimated: this is the actual code, the actual JVM, doing the actual work,
measured while it was stuck.

**Not fixed in this pass** — this rehearsal's purpose was to measure and report, not to
silently patch a load-bearing service mid-drill. The fix shape is well understood
(periodic `entityManager.flush(); entityManager.clear()` inside
`FindingReconciler.reconcile`, or batching candidates into bounded-size transactions)
and is a reasonable next PR, but that's a decision for whoever owns detection's
transaction boundaries, not something to slip in as a side effect of a performance
rehearsal.

## What this rehearsal does and doesn't cover

- **Covers**: the actual grid query path at 50k rows (passes comfortably), and the
  actual detection sweep at 50k rows (does not complete — real finding, not a
  hypothetical one).
- **Doesn't cover**: HTTP-layer latency (no real Keycloak JWT was available to call the
  endpoints over HTTP in this environment — the grid measurement calls the repository
  directly, one layer under the controller), concurrent/multi-user load (this was a
  single-threaded measurement), and the client-side grid virtualization question, which
  is a frontend gap unrelated to what Postgres or the JVM can do.
