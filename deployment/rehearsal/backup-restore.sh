#!/usr/bin/env bash
# VYB-0959 (F11): a backup and restore rehearsal of the Vyoog database, run against a LOCAL PostgreSQL only.
#
#   PGPASSWORD=... deployment/rehearsal/backup-restore.sh <source-database> [<target-database>]
#
# Takes a custom-format dump of the source, restores it into a NEW empty database (never over the source), then proves the copy is
# the same: every table's row count, the extensions, and the Flyway history. It times the dump and the restore. Booting the
# application against the restored copy is the last step and is done by hand (the command is printed): see
# docs/08-architecture/deployment/pilot-rehearsals.md. Refuses any host that is not this machine. Never run it against a shared or
# deployed database: it only reads the source, but the restore target is created and dropped by name.
set -euo pipefail

SOURCE="${1:?usage: backup-restore.sh <source-database> [<target-database>]}"
TARGET="${2:-${SOURCE}_restored}"
HOST="${PGHOST:-localhost}"
PORT="${PGPORT:-5432}"
USER_NAME="${PGUSER:-postgres}"
SCHEMA="${SCHEMA:-vyg_requirement}"
JOBS="${JOBS:-4}"

case "$HOST" in
  localhost|127.0.0.1|::1) ;;
  *) echo "Refusing: $HOST is not this machine. This rehearsal runs only against a local PostgreSQL." >&2; exit 2 ;;
esac
[ "$SOURCE" != "$TARGET" ] || { echo "The target must differ from the source." >&2; exit 2; }

psql_() { psql -h "$HOST" -p "$PORT" -U "$USER_NAME" -v ON_ERROR_STOP=1 -At "$@"; }
now() { date +%s.%N; }
elapsed() { awk -v a="$1" -v b="$2" 'BEGIN { printf "%.1f", b - a }'; }

DUMP="$(mktemp -d)/${SOURCE}.dump"
echo "== source: $SOURCE ($(psql_ -d "$SOURCE" -c "select pg_size_pretty(pg_database_size(current_database()))"))"

t0=$(now)
pg_dump -h "$HOST" -p "$PORT" -U "$USER_NAME" -d "$SOURCE" -Fc -f "$DUMP"
t1=$(now)
echo "== dump: $(elapsed "$t0" "$t1") s, $(du -h "$DUMP" | cut -f1)"

psql_ -d postgres -c "DROP DATABASE IF EXISTS \"$TARGET\"" -c "CREATE DATABASE \"$TARGET\""
# The extensions the schema needs exist in the dump's own CREATE EXTENSION statements; pg_restore creates them.
t2=$(now)
pg_restore -h "$HOST" -p "$PORT" -U "$USER_NAME" -d "$TARGET" -j "$JOBS" --exit-on-error "$DUMP"
t3=$(now)
echo "== restore ($JOBS jobs): $(elapsed "$t2" "$t3") s"

counts() {
  psql_ -d "$1" -c "
    SELECT table_name || ' ' || (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text
    FROM information_schema.tables WHERE table_schema = '$SCHEMA' AND table_type = 'BASE TABLE' ORDER BY 1"
}
extensions() { psql_ -d "$1" -c "select extname from pg_extension order by 1"; }
history() { psql_ -d "$1" -c "select version || ':' || success from $SCHEMA.flyway_schema_history order by installed_rank"; }

status=0
for what in counts extensions history; do
  if diff <("$what" "$SOURCE") <("$what" "$TARGET") > /dev/null; then
    echo "== $what: identical ($("$what" "$SOURCE" | wc -l) lines)"
  else
    echo "== $what: DIFFERENT"; diff <("$what" "$SOURCE") <("$what" "$TARGET") | head -10; status=1
  fi
done
echo "== tables: $(psql_ -d "$TARGET" -c "select count(*) from information_schema.tables where table_schema='$SCHEMA'"), rows in requirement: $(psql_ -d "$TARGET" -c "select count(*) from $SCHEMA.requirement")"
echo "== to boot the application against the copy:"
echo "   DB_URL='jdbc:postgresql://$HOST:$PORT/$TARGET?currentSchema=$SCHEMA,public' DB_USER=$USER_NAME DB_PASSWORD=... java -jar backend/vyoog-api/target/vyoog-api-*.jar --server.port=8081"
echo "== drop the copy when done:  psql -h $HOST -U $USER_NAME -d postgres -c 'DROP DATABASE \"$TARGET\"'"
rm -f "$DUMP"
exit $status
