# rehearsal

Scripts for the load and recovery rehearsals (VYB-0959). Results and what they do and do not show: [`docs/08-architecture/deployment/pilot-rehearsals.md`](../../docs/08-architecture/deployment/pilot-rehearsals.md).

| Script | What |
|---|---|
| `backup-restore.sh <database>` | dumps a database, restores it into a **new** one, compares every table's row count, the extensions and the Flyway history, and prints how to boot the application against the copy |
| `pitr.sh <database>` | builds a throwaway PostgreSQL cluster with WAL archiving, loads a copy of the database, takes a base backup, makes a mistake, destroys the cluster and recovers to a chosen moment and to the end of the archive |

Both run only against PostgreSQL on **this machine** and refuse any other host. `backup-restore.sh` creates and drops its target by name (`<database>_restored`); `pitr.sh` works in `/tmp/vyoog-pitr` (override with the second argument) on port 5439 and needs the PostgreSQL server binaries and, as root, `runuser`. Neither writes to the source. Never point them at a shared or deployed database.

```bash
PGPASSWORD=... deployment/rehearsal/backup-restore.sh vygload50
PGPASSWORD=... deployment/rehearsal/pitr.sh vygload50
```

The load runner is a test class, not a script: `backend/vyoog-api/src/test/java/com/vyoog/api/LoadRehearsalHttpRunner.java` (its Javadoc has the command).
