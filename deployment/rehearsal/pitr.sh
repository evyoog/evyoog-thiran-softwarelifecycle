#!/usr/bin/env bash
# VYB-0959 (F11): a point-in-time recovery rehearsal, on a THROWAWAY PostgreSQL cluster it creates under a scratch directory.
#
#   PGPASSWORD=... deployment/rehearsal/pitr.sh <source-database> [<scratch-dir>]
#
# What it does: stands up a new cluster with WAL archiving on, loads a copy of <source-database> (from the local server) into it, takes a
# base backup, writes known rows, records a moment T, then makes a "mistake" (deletes every approved requirement and the rows it
# wrote) and writes more rows after it. It then destroys that cluster, rebuilds one from the base backup and the archived WAL only,
# recovers to exactly T, and checks the data matches what it recorded at T. A second recovery to the end of the archive shows what a
# total loss of the primary costs: the writes since the last archived WAL segment (the recovery point actually achieved).
# Times each stage. Refuses a non-local source. Needs root only to switch to the postgres user.
set -euo pipefail

SOURCE="${1:?usage: pitr.sh <source-database> [<scratch-dir>]}"
WORK="${2:-/tmp/vyoog-pitr}"
SRC_HOST="${PGHOST:-localhost}"; SRC_PORT="${PGPORT:-5432}"; SRC_USER="${PGUSER:-postgres}"
PORT=5439
SCHEMA="${SCHEMA:-vyg_requirement}"
BIN="$(ls -d /usr/lib/postgresql/*/bin | sort -V | tail -1)"
ARCHIVE_TIMEOUT="${ARCHIVE_TIMEOUT:-60}"

case "$SRC_HOST" in localhost|127.0.0.1|::1) ;; *) echo "Refusing: $SRC_HOST is not this machine." >&2; exit 2 ;; esac
[ "$(id -u)" = 0 ] && AS_PG=(runuser -u postgres --) || AS_PG=()
pg() { "${AS_PG[@]}" "$BIN/$1" "${@:2}"; }
q() { "${AS_PG[@]}" "$BIN/psql" -h localhost -p "$1" -U postgres -d "$2" -v ON_ERROR_STOP=1 -At -c "$3"; }
now() { date +%s.%N; }
secs() { awk -v a="$1" -v b="$2" 'BEGIN { printf "%.1f", b - a }'; }

rm -rf "$WORK"; mkdir -p "$WORK/archive"; chown -R postgres:postgres "$WORK" 2>/dev/null || true
ARCH="$WORK/archive"

# ---------------------------------------------------------------- the primary
pg initdb -D "$WORK/primary" -U postgres --auth=trust >/dev/null
cat >> "$WORK/primary/postgresql.conf" <<CONF
port = $PORT
listen_addresses = 'localhost'
unix_socket_directories = '$WORK'
wal_level = replica
archive_mode = on
archive_command = 'test ! -f $ARCH/%f && cp %p $ARCH/%f'
archive_timeout = ${ARCHIVE_TIMEOUT}s
max_wal_senders = 4
CONF
pg pg_ctl -D "$WORK/primary" -l "$WORK/primary.log" -w start >/dev/null
q $PORT postgres "create database vyoog" >/dev/null

echo "== loading a copy of $SOURCE into the new cluster"
t=$(now)
pg_dump -h "$SRC_HOST" -p "$SRC_PORT" -U "$SRC_USER" -d "$SOURCE" -Fc -f "$WORK/source.dump"
"${AS_PG[@]}" "$BIN/pg_restore" -h localhost -p $PORT -U postgres -d vyoog -j 4 --exit-on-error "$WORK/source.dump"
echo "   loaded in $(secs "$t" "$(now)") s; requirements: $(q $PORT vyoog "select count(*) from $SCHEMA.requirement")"

# ---------------------------------------------------------------- base backup, then writes
t=$(now)
pg pg_basebackup -h localhost -p $PORT -U postgres -D "$WORK/base" -Fp -X stream -c fast
BASE_SECS=$(secs "$t" "$(now)")
echo "== base backup: $BASE_SECS s, $(du -sh "$WORK/base" | cut -f1)"

q $PORT vyoog "insert into $SCHEMA.requirement (key, type, status, priority, title, statement) select 'PITR-BEFORE-' || n, 'FUNCTIONAL', 'APPROVED', 'MEDIUM', 'Before the mistake ' || n, 'The system shall be recovered.' from generate_series(1, 100) n" >/dev/null
sleep 1
AT_T=$(q $PORT vyoog "select now()")
EXPECT_TOTAL=$(q $PORT vyoog "select count(*) from $SCHEMA.requirement")
EXPECT_APPROVED=$(q $PORT vyoog "select count(*) from $SCHEMA.requirement where status = 'APPROVED'")
EXPECT_TABLES=$(q $PORT vyoog "select string_agg(table_name || ':' || (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text, ',' order by table_name) from information_schema.tables where table_schema = '$SCHEMA' and table_type = 'BASE TABLE'")
echo "== T = $AT_T (requirements $EXPECT_TOTAL, approved $EXPECT_APPROVED)"
sleep 2

echo "== the mistake: every approved requirement deleted, then more writes"
MISTAKE_AT=$(date +%s.%N)
q $PORT vyoog "delete from $SCHEMA.requirement where status = 'APPROVED'" >/dev/null
q $PORT vyoog "insert into $SCHEMA.requirement (key, type, status, priority, title, statement) select 'PITR-AFTER-' || n, 'FUNCTIONAL', 'DRAFT', 'LOW', 'After the mistake ' || n, 'x' from generate_series(1, 50) n" >/dev/null
q $PORT vyoog "select pg_switch_wal()" >/dev/null          # the segment holding all of the above is archived
sleep 2
LAST_ARCHIVED_COMMIT=$(q $PORT vyoog "select now()")
q $PORT vyoog "insert into $SCHEMA.requirement (key, type, status, priority, title, statement) values ('PITR-UNARCHIVED', 'FUNCTIONAL', 'DRAFT', 'LOW', 'Written after the last archived segment', 'x')" >/dev/null
LOST_COMMIT=$(q $PORT vyoog "select now()")
# let the archiver finish what it was handed, then the primary is destroyed with the unarchived row still only in its own pg_wal
sleep 3
CRASH_AT=$(date +%s.%N)
pg pg_ctl -D "$WORK/primary" -m immediate stop >/dev/null
mv "$WORK/primary" "$WORK/primary.destroyed"
echo "== the primary is gone. archived WAL segments: $(ls "$ARCH" | grep -vc history)"

# ---------------------------------------------------------------- recover to T
recover() {  # recover <name> <target-time or "">  -> prints seconds until it accepts connections and is writable
  local name="$1" target="$2" dir="$WORK/$1"
  rm -rf "$dir"; cp -a "$WORK/base" "$dir"; chmod 700 "$dir"
  rm -f "$dir/postmaster.pid" "$dir/backup_label.old"
  {
    echo "port = $PORT"; echo "listen_addresses = 'localhost'"; echo "unix_socket_directories = '$WORK'"
    echo "archive_mode = off"
    echo "restore_command = 'cp $ARCH/%f %p'"
    [ -n "$target" ] && echo "recovery_target_time = '$target'"
    echo "recovery_target_action = 'promote'"
    echo "recovery_target_inclusive = true"
  } >> "$dir/postgresql.conf"
  touch "$dir/recovery.signal"
  local t0; t0=$(now)
  pg pg_ctl -D "$dir" -l "$WORK/$name.log" -w -t 600 start >/dev/null
  until [ "$(q $PORT vyoog "select pg_is_in_recovery()" 2>/dev/null || echo t)" = "f" ]; do sleep 0.5; done
  RECOVERY_SECS=$(secs "$t0" "$(now)")
}

echo "== recovery 1: to T"
recover to-t "$AT_T"
GOT_TOTAL=$(q $PORT vyoog "select count(*) from $SCHEMA.requirement")
GOT_APPROVED=$(q $PORT vyoog "select count(*) from $SCHEMA.requirement where status = 'APPROVED'")
GOT_TABLES=$(q $PORT vyoog "select string_agg(table_name || ':' || (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text, ',' order by table_name) from information_schema.tables where table_schema = '$SCHEMA' and table_type = 'BASE TABLE'")
echo "   recovered and writable after $RECOVERY_SECS s (base backup restore and WAL replay)"
echo "   requirements: expected $EXPECT_TOTAL, got $GOT_TOTAL; approved: expected $EXPECT_APPROVED, got $GOT_APPROVED"
echo "   rows written after T present: $(q $PORT vyoog "select count(*) from $SCHEMA.requirement where key like 'PITR-AFTER-%' or key = 'PITR-UNARCHIVED'") (expected 0)"
[ "$EXPECT_TABLES" = "$GOT_TABLES" ] && echo "   every table's row count matches the moment T" || { echo "   TABLE COUNTS DIFFER from the moment T"; exit 1; }
[ "$EXPECT_TOTAL" = "$GOT_TOTAL" ] && [ "$EXPECT_APPROVED" = "$GOT_APPROVED" ] || exit 1
pg pg_ctl -D "$WORK/to-t" -m fast stop >/dev/null

# ---------------------------------------------------------------- recover to the end of the archive: what a total loss costs
echo "== recovery 2: to the end of the archive (the primary's own pg_wal is gone)"
recover to-end ""
echo "   recovered and writable after $RECOVERY_SECS s"
echo "   PITR-AFTER rows present: $(q $PORT vyoog "select count(*) from $SCHEMA.requirement where key like 'PITR-AFTER-%'") (expected 50: they are in an archived segment)"
LOST=$(q $PORT vyoog "select count(*) from $SCHEMA.requirement where key = 'PITR-UNARCHIVED'")
echo "   PITR-UNARCHIVED present: $LOST (expected 0: it was committed after the last archived segment)"
echo "   so the recovery point achieved: the data is complete up to $LAST_ARCHIVED_COMMIT; the write committed at $LOST_COMMIT was lost;"
echo "   with archive_timeout=${ARCHIVE_TIMEOUT}s a segment is archived at least every ${ARCHIVE_TIMEOUT}s, which bounds the loss to about that long (plus the archiver's own delay)"
pg pg_ctl -D "$WORK/to-end" -m fast stop >/dev/null
echo "== done. scratch directory: $WORK (delete with: rm -rf $WORK)"
