# Running MinIO locally

MinIO is the S3-compatible object store Vyoog writes requirement **attachments** to
(VYB-0123). Nothing else uses it: the register, requirements and audit trail all live in
Postgres. If MinIO is down, the app starts and everything works except uploading,
downloading and tenant-export bundling of attachments.

## First: is it already running?

MinIO commonly stays up for days, so check before you start it — a second instance on the
same data directory is refused, and correctly so.

```bash
pgrep -a minio && curl -s -o /dev/null -w 'live -> %{http_code}\n' http://localhost:9000/minio/health/live
```

A pid and `live -> 200` means you are done; go straight to the backend.

If you tried to start it and got:

```
FATAL Unable to start the server: Specified port is already in use
```

then an earlier MinIO still holds 9000/9001 — often one left running for days. Either use
it (it is the same store, same data) or take the port back and start your own:

```bash
pkill -x minio
```

Nothing is lost either way: the data lives in `~/.local/minio-data`, not in the process.

## The command

```bash
MINIO_ROOT_USER=minio MINIO_ROOT_PASSWORD=minio123 \
  ~/.local/bin/minio server ~/.local/minio-data --console-address :9001
```

That is it. Start it **before** the backend — `AttachmentService` does a
`headBucket`/`createBucket` on startup, so the bucket is created for you on first run.

| | |
|---|---|
| S3 API | <http://localhost:9000> |
| Web console | <http://localhost:9001> (sign in `minio` / `minio123`) |
| Data directory | `~/.local/minio-data` |
| Bucket | `vyoog-attachments` (auto-created) |

Leave it running in its own terminal. `Ctrl+C` stops it. Nothing is lost — the data
directory is on disk, not in the process.

### Why not `docker compose up`

`CLAUDE.md` and `docker-compose.yml` describe bringing MinIO up with Docker. **Docker is
not installed on this machine** (`docker: command not found`), so that path does not work
here. The binary at `~/.local/bin/minio` is the working substitute and produces the same
thing on the same ports. Postgres is likewise a native install on `localhost:5432` rather
than the compose service.

## Why these exact values

They are not arbitrary — every one is the default the backend already compiles in, so a
correctly-started MinIO needs **no** environment variables on the Spring side:

| Setting | Backend default | Declared in |
|---|---|---|
| `vyoog.storage.endpoint` | `http://localhost:9000` | `StorageConfig` |
| `vyoog.storage.access-key` | `minio` | `StorageConfig` |
| `vyoog.storage.secret-key` | `minio123` | `StorageConfig` |
| `vyoog.storage.region` | `us-east-1` | `StorageConfig` |
| `vyoog.storage.bucket` | `vyoog-attachments` | `AttachmentService` |

Change the root user or password on the MinIO side and you must override
`vyoog.storage.access-key` / `secret-key` to match, or every attachment call fails with a
403 that reads like a bug in Vyoog.

`StorageConfig` also sets `forcePathStyle(true)` — MinIO puts the bucket in the path
rather than in a subdomain, and the AWS SDK assumes the opposite by default.

## Checking it is actually up

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:9000/minio/health/live   # expect 200
ss -ltnp | grep -E ':(9000|9001)'                                                  # expect both ports
ls ~/.local/minio-data                                                             # expect vyoog-attachments
```

To see what is already running and with which credentials:

```bash
pgrep -a minio
tr '\0' '\n' < /proc/$(pgrep -x minio)/environ | grep '^MINIO_'
```

## Running it in the background

For a session where you do not want a terminal tied up:

```bash
MINIO_ROOT_USER=minio MINIO_ROOT_PASSWORD=minio123 \
  nohup ~/.local/bin/minio server ~/.local/minio-data --console-address :9001 \
  > /tmp/minio.log 2>&1 &
```

Stop it with `pkill -x minio`. Read `/tmp/minio.log` if it will not start.

## The full local stack

Three terminals, in this order:

```bash
# 1. MinIO
MINIO_ROOT_USER=minio MINIO_ROOT_PASSWORD=minio123 \
  ~/.local/bin/minio server ~/.local/minio-data --console-address :9001

# 2. Backend — port 8080
cd ~/eVyoogEIS/vyg-requirement && ./run-local.sh

# 3. Frontend — port 5173
cd ~/eVyoogEIS/vyg-requirement-ui && npm run dev
```

Order matters only for MinIO before the backend. Two things worth knowing about step 2:

- `run-local.sh` sources `.env` (copy `.env.example`; it lists every required variable),
  refuses to run unless `DB_URL` points at localhost, then runs
  `./mvnw -B -pl vyoog-api spring-boot:run`.
- Because it uses `-pl vyoog-api`, the `vyoog-domain` dependency is resolved from
  `~/.m2`, **not** from its sibling `target/` directory. After changing anything in
  `vyoog-domain`, run `./mvnw -B -DskipTests install` first or the running app silently
  uses stale classes.

The backend has no default datasource (`docs/DECISIONS.md` D22, which supersedes D9): it
refuses to start unless `DB_URL` / `DB_USER` / `DB_PASSWORD` are set, so a locally-run
stack only ever touches the database you named — use the docker-compose one.

## When it goes wrong

**`Specified port is already in use` / `Address already in use`** — almost always your own
MinIO, still running from earlier; see the check at the top. `pgrep -a minio` confirms it,
and `ps -o etime -p $(pgrep -x minio)` shows how long it has been up. Only kill it
(`pkill -x minio`) if you actually want a fresh process — a second instance on the same
data directory is refused because running two would corrupt it.

**Attachment upload returns 403** — the running MinIO's root credentials do not match the
backend's `vyoog.storage.access-key`/`secret-key`. Compare them with the `/proc` command
above.

**Attachment upload returns a connection error** — MinIO is not running, or the backend
was started with a `vyoog.storage.endpoint` pointing somewhere else. The health probe
above distinguishes the two in one command.

**Bucket missing** — it is created by `AttachmentService` at backend startup, so this only
happens if MinIO came up *after* the backend. Restart the backend; nothing needs creating
by hand.
