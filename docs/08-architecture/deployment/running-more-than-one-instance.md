# Running more than one instance, and watching it (VYB-0909)

What changed so that two or more API instances can run behind one load balancer, and so that they can be observed and contained.

## Metrics: Prometheus

`micrometer-registry-prometheus` is on the API's classpath, so `GET /actuator/prometheus` serves the JVM, HTTP-server, database-pool and application metrics in Prometheus text format. (`management.endpoints.web.exposure.include` already listed `prometheus`; before this change there was no registry behind the name.)

The endpoint is **not public**. It needs a bearer token like every other `/actuator` path except `/actuator/health/**`. Configure the scraper with a token for a Keycloak service account. There is no separate management port. If an internal-only port is wanted later, that is a new decision.

Application metrics added here: `vyoog_scheduler_runs_total{job, outcome}` where outcome is `ran`, `skipped` (another instance held the lease) or `failed`. A job whose `ran` count stops rising on every instance is a job nobody is running.

## Scheduled jobs run on one instance at a time

Every scheduled trigger is in one class, `com.vyoog.api.scheduling.ScheduledJobs`, and each is wrapped in `SchedulerLock`. A build-time ArchUnit test fails if a `@Scheduled` method appears anywhere else.

| Job | When | Lease (at most / at least) |
|---|---|---|
| `outbox-relay` | every 2 s after the previous run ends | 30 s / none |
| `detection-sweep` | 02:00 | 2 h / 10 min |
| `audit-retention` | 02:15 | 1 h / 10 min |
| `clarification-escalation` | 02:30 | 1 h / 10 min |
| `purge-expired-records` | 03:00 | 1 h / 10 min |

How the lease works (`scheduler_lock`, migration V036):

- One row per job. An instance takes it with a single atomic `INSERT ... ON CONFLICT DO UPDATE ... WHERE the lease has lapsed`, using the database's clock. An instance that does not get it skips that run.
- **At most** is the lease. If an instance dies mid-job the lease lapses by itself after this time. Set it above the job's real duration.
- **At least** keeps the lease after a short job finishes, so a second instance whose cron fires a moment later does not run the job again. The relay has none because it is meant to run back to back.
- The lock is taken and released in its own transactions, and the job's own transaction commits before the release. So the next instance never reads rows the previous one has not yet committed.
- A holder whose lease lapsed cannot release the lease of the instance that took over (each acquisition has its own token).

A manual sweep (`POST /api/v1/findings/sweep`, rate limited) still only guards against a second sweep **on the same instance**. It does not take the lease.

`scheduler_lock` has one row per job and is updated twice per relay cycle; autovacuum is enough.

## nginx limits

`deployment/nginx/frontend.conf`. The numbers are generous on purpose: they stop a runaway client or a guessing loop, not users.

| Limit | Value |
|---|---|
| Request rate per address, `/api/` | 50 r/s, burst 100 |
| Request rate per address, `/api/v1/auth/` | 5 r/s, burst 10 (on top of the above) |
| Concurrent connections per address | 100 |
| Request body | 12 MB (the API's own `max-request-size`) |
| Header / body / send timeouts | 15 s / 30 s / 30 s |
| Upstream read timeout | 120 s (document analysis waits up to 90 s on the model) |
| Notification stream | unbuffered, 1 h read timeout, own rate zone |

Refusals are `429` (rate, connections) or `413` (body).

**Behind a load balancer, set the real client address first.** nginx keys its limits on the address it sees. Without `set_real_ip_from <balancer CIDR>; real_ip_header X-Forwarded-For; real_ip_recursive on;` every user shares the balancer's address and one bucket. The config explains this at the top; it is not set here because the balancer's address is not known to this repository.

## Containers

- **Backend**: runs as `vyoog` (uid/gid 10001), not root. `HEALTHCHECK` calls `/actuator/health/liveness`, which is open without a token. Liveness, not readiness, so a database outage takes the instance out of rotation without restarting it.
- **Frontend**: `nginxinc/nginx-unprivileged` (uid 101), so it **listens on 8080, not 80**. Whatever maps a port to this image (load balancer target group, ECS port mapping) must point at 8080. `HEALTHCHECK` calls `/healthz`, answered by nginx itself.

Neither image has been built in the environment that made this change (no Docker daemon). The nginx config was loaded and exercised with a local nginx (rate limits, 413, health, SPA fallback); the Dockerfiles were not.

## Purge of bookkeeping tables (VYB-0910)

The 03:00 job (`PurgeService`) deletes, in batches of 5,000 with one transaction per batch:

| Table | Kept | Setting |
|---|---|---|
| `idempotency_key` | 7 days | `IDEMPOTENCY_RETENTION_DAYS` |
| `webhook_delivery` | 90 days | `WEBHOOK_DELIVERY_RETENTION_DAYS` |
| `connector_sync_log` | 90 days | `CONNECTOR_SYNC_LOG_RETENTION_DAYS` |
| `rate_limit_hit` | 1 hour | fixed |

Values below 1 stop the application at startup. When a run deletes anything it writes one `retention.purged` system audit event with the counts, and `vyoog_purge_deleted_total{table}` counts rows for the scrape.

Two things to know when changing the numbers. A client that retries a create with the same idempotency key after the window gets a second row, not the first response. And the webhook window is the replay-protection window: the signed payload has no timestamp, so once a delivery id is purged a captured delivery with that id would be accepted again.
