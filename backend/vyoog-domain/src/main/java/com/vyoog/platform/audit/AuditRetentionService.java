package com.vyoog.platform.audit;

import com.vyoog.platform.config.AppConfigService;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0723: {@code app_config.audit_retention_days} has existed since V007 and been
 * readable/settable since session 12 — nothing ever acted on it. This does: keeps
 * enough future monthly partitions of {@code audit_event} (V010) always available,
 * and archives (detaches and renames — never drops) whole partitions once every row
 * in them is past the retention window.
 *
 * <p>Archival is a real DDL operation ({@code ALTER TABLE ... DETACH PARTITION}), not
 * a bulk {@code DELETE} — a genuine requirement, not a shortcut: {@code audit_event}'s
 * append-only trigger (V001) fires on any row-level {@code DELETE}, so a bulk delete
 * of "old" rows would simply fail. Detaching a partition is schema-level, not
 * row-level, so it never touches that trigger at all, and the archived data is
 * renamed in place, not destroyed — "archived", not "deleted".
 */
@Service
public class AuditRetentionService {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionService.class);
    private static final int MONTHS_AHEAD = 6;

    private final JdbcTemplate jdbc;
    private final AppConfigService config;

    public AuditRetentionService(JdbcTemplate jdbc, AppConfigService config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    public record PartitionInfo(String name, LocalDate rangeStart, LocalDate rangeEnd, boolean archived, long rowCount) {}

    /** VYB-0723: what actually exists today — for the Administration screen, not a guess. */
    public List<PartitionInfo> listPartitions() {
        // pg_inherits gives the live parent→child relationship directly, rather than
        // guessing partition names from a naming convention — a partition someone
        // created or renamed by hand still shows up correctly.
        List<String> childTables = jdbc.queryForList("""
            SELECT c.relname FROM pg_inherits i
            JOIN pg_class p ON p.oid = i.inhparent
            JOIN pg_class c ON c.oid = i.inhrelid
            WHERE p.relname = 'audit_event'
            ORDER BY c.relname
            """, String.class);
        List<PartitionInfo> result = new ArrayList<>();
        for (String table : childTables) {
            long rows = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
            var bounds = partitionBounds(table);
            result.add(new PartitionInfo(table, bounds[0], bounds[1], false, rows));
        }
        for (String archived : archivedTableNames()) {
            long rows = jdbc.queryForObject("SELECT count(*) FROM " + archived, Long.class);
            result.add(new PartitionInfo(archived, null, null, true, rows));
        }
        return result;
    }

    private LocalDate[] partitionBounds(String table) {
        // audit_event_default has no FOR VALUES clause to parse — bounds stay null.
        if (table.equals("audit_event_default")) return new LocalDate[] {null, null};
        String def = jdbc.queryForObject(
            "SELECT pg_get_expr(c.relpartbound, c.oid) FROM pg_class c WHERE c.relname = ?", String.class, table);
        // occurred_at is TIMESTAMPTZ, so Postgres renders full timestamps with a zone
        // offset here, e.g. "FOR VALUES FROM ('2026-07-01 00:00:00+05:30') TO
        // ('2026-08-01 00:00:00+05:30')" — not bare dates. Every partition this service
        // creates is month-aligned at local midnight, so the first 10 characters of
        // each literal are exactly the date; day-level granularity is all retention
        // comparisons need.
        try {
            String[] parts = def.replace("FOR VALUES FROM (", "").replace(")", "").split(" TO \\(");
            return new LocalDate[] {
                LocalDate.parse(parts[0].replace("'", "").substring(0, 10)),
                LocalDate.parse(parts[1].replace("'", "").substring(0, 10)),
            };
        } catch (Exception e) {
            return new LocalDate[] {null, null};
        }
    }

    private List<String> archivedTableNames() {
        return jdbc.queryForList(
            "SELECT tablename FROM pg_tables WHERE schemaname = current_schema() AND tablename LIKE 'audit_event_archive_%'",
            String.class);
    }

    /** Daily at 02:15 (triggered by {@code ScheduledJobs}): keep the partition window from ever running dry, independent of retention. */
    public void scheduledMaintenance() {
        ensureFuturePartitions();
        archiveEligiblePartitions();
    }

    @Transactional
    public int ensureFuturePartitions() {
        YearMonth current = YearMonth.now();
        int created = 0;
        for (int i = 0; i <= MONTHS_AHEAD; i++) {
            YearMonth ym = current.plusMonths(i);
            String name = "audit_event_" + ym.toString().replace("-", "_");
            LocalDate start = ym.atDay(1);
            LocalDate end = ym.plusMonths(1).atDay(1);
            Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM pg_tables WHERE schemaname = current_schema() AND tablename = ?", Integer.class, name);
            if (exists != null && exists == 0) {
                jdbc.execute(String.format(
                    "CREATE TABLE %s PARTITION OF audit_event FOR VALUES FROM ('%s') TO ('%s')", name, start, end));
                created++;
                log.info("[audit-retention] created partition {} for [{}, {})", name, start, end);
            }
        }
        return created;
    }

    /**
     * VYB-0723 AC1: archives a partition once its entire range is older than
     * {@code audit_retention_days} — the whole partition, not row-by-row, and only
     * once every row it could ever hold has aged out (a partition still receiving
     * writes is never touched).
     */
    @Transactional
    public List<String> archiveEligiblePartitions() {
        int retentionDays = config.auditRetentionDays();
        LocalDate cutoff = LocalDate.now().minusDays(retentionDays);
        List<String> archived = new ArrayList<>();

        List<String> childTables = jdbc.queryForList("""
            SELECT c.relname FROM pg_inherits i
            JOIN pg_class p ON p.oid = i.inhparent
            JOIN pg_class c ON c.oid = i.inhrelid
            WHERE p.relname = 'audit_event' AND c.relname != 'audit_event_default'
            """, String.class);

        for (String table : childTables) {
            LocalDate[] bounds = partitionBounds(table);
            if (bounds[1] == null) continue; // couldn't parse — leave it alone rather than guess
            LocalDate rangeEnd = bounds[1]; // exclusive upper bound
            if (!rangeEnd.isAfter(cutoff)) {
                String archiveName = table.replace("audit_event_", "audit_event_archive_");
                jdbc.execute("ALTER TABLE audit_event DETACH PARTITION " + table);
                jdbc.execute("ALTER TABLE " + table + " RENAME TO " + archiveName);
                archived.add(archiveName);
                log.info("[audit-retention] archived {} -> {} (partition range ended {}, retention {} days)",
                    table, archiveName, rangeEnd, retentionDays);
            }
        }
        return archived;
    }
}
