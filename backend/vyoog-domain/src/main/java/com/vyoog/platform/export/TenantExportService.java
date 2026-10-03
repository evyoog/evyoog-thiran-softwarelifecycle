package com.vyoog.platform.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.attachments.AttachmentService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0732: "export a tenant's entire dataset" for a deployment that has exactly one
 * tenant (docs/DECISIONS.md D3) means exporting everything — every table below, as
 * plain rows, with no per-entity mapping code to maintain as new tables arrive. AC1:
 * the manifest names every table and its row count, so the export describes its own
 * shape rather than requiring the schema alongside it to make sense of. AC2: a single
 * {@code REPEATABLE READ} transaction gives every table a consistent snapshot without
 * locking anything a concurrent writer would notice.
 *
 * <p>{@link #export()} stays metadata-only (the {@code attachment}/{@code
 * attachment_version} rows, not the bytes) for callers that just want the row-level
 * snapshot quickly. {@link #exportBundle()} is the real full export: the same
 * manifest plus every attachment's actual bytes fetched live from object storage and
 * zipped alongside it, closing the gap this class used to just disclose.
 */
@Service
public class TenantExportService {

    private final JdbcTemplate jdbc;
    private final AttachmentService attachments;
    private final ObjectMapper objectMapper;

    public TenantExportService(JdbcTemplate jdbc, AttachmentService attachments, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.attachments = attachments;
        this.objectMapper = objectMapper;
    }

    /**
     * Tables that are in the schema but are not application data, so an export leaves them out:
     * Flyway's own bookkeeping, and two tables of per-instance operational state (rate-limit cooldowns,
     * scheduled-job leases) that mean nothing in another environment and would be harmful to restore.
     * Detached audit archives ({@code audit_event_archive_*}) are also left out: retention moved them
     * out of the live table on purpose and they can be large. Everything else is exported.
     */
    public static final Set<String> NOT_EXPORTED = Set.of("flyway_schema_history", "rate_limit_hit", "scheduler_lock");
    private static final String AUDIT_ARCHIVE_PREFIX = "audit_event_archive_";

    /**
     * VYB-0911 (F23): read from the schema, not from a hand-kept list. The list this replaced named 56
     * tables and had silently fallen behind by ten as migrations added more, while its comment claimed
     * a complete accounting. A table added by a future migration is now exported without anyone
     * remembering to say so; to leave one out, add it to {@link #NOT_EXPORTED} with the reason.
     *
     * <p>Partitions are skipped (the partitioned parent, {@code audit_event}, reads them all). Names
     * come from the catalog and are quoted, never from input.
     */
    private List<String> tablesToExport() {
        return jdbc.queryForList("""
            SELECT c.relname FROM pg_class c
             WHERE c.relnamespace = current_schema()::regnamespace AND c.relkind IN ('r', 'p') AND NOT c.relispartition
             ORDER BY c.relname
            """, String.class).stream()
            .filter(t -> !NOT_EXPORTED.contains(t) && !t.startsWith(AUDIT_ARCHIVE_PREFIX))
            .toList();
    }

    private static String quoted(String table) {
        return "\"" + table.replace("\"", "\"\"") + "\"";
    }

    public record TableDump(String table, long rowCount, List<Map<String, Object>> rows) {}
    public record Manifest(Instant generatedAt, List<TableDump> tables, long totalRows) {}

    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public Manifest export() {
        return buildManifest();
    }

    private Manifest buildManifest() {
        List<TableDump> dumps = new ArrayList<>();
        long total = 0;
        for (String table : tablesToExport()) {
            // No catch: a table that cannot be read is a failed export, not an empty table. (It used to
            // be swallowed here, and inside this transaction Postgres would then refuse every later
            // query, so one bad table silently emptied the rest of the manifest.)
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM " + quoted(table));
            dumps.add(new TableDump(table, rows.size(), rows));
            total += rows.size();
        }
        return new Manifest(Instant.now(), dumps, total);
    }

    /**
     * @param zipBytes manifest.json (the same shape {@link #export()} returns) plus
     *     one entry under {@code attachments/} per successfully-fetched storage key.
     * @param attachmentCount how many {@code attachment_version} rows the manifest snapshot contained.
     * @param bundledCount how many of those were actually fetched and zipped.
     * @param missingStorageKeys keys the manifest listed but object storage couldn't
     *     produce (deleted out-of-band, or the store was unreachable this run) — named
     *     rather than silently dropped, so the export discloses its own gaps the same
     *     way a missing/older table already does in {@link #export()}.
     */
    public record ExportBundle(
        byte[] zipBytes, int attachmentCount, int bundledCount, List<String> missingStorageKeys) {}

    /**
     * VYB-0732: the real full export — {@link #buildManifest()}'s same consistent
     * snapshot, plus every attachment's bytes fetched live from object storage
     * (MinIO/S3, via {@link AttachmentService#fetchByStorageKey}) and zipped
     * alongside it. A storage fetch failure for one object doesn't abort the whole
     * bundle — it's recorded in {@code missingStorageKeys} instead, consistent with
     * how a missing table is handled in {@link #buildManifest()}.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public ExportBundle exportBundle() {
        Manifest manifest = buildManifest();
        List<Map<String, Object>> attachmentVersionRows = manifest.tables().stream()
            .filter(t -> "attachment_version".equals(t.table()))
            .findFirst()
            .map(TableDump::rows)
            .orElse(List.of());

        List<String> missing = new ArrayList<>();
        int bundled = 0;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(objectMapper.writeValueAsBytes(manifest));
            zip.closeEntry();

            java.util.Set<String> seenKeys = new java.util.HashSet<>();
            for (Map<String, Object> row : attachmentVersionRows) {
                Object key = row.get("storage_key");
                if (key == null || !seenKeys.add(key.toString())) continue; // defensive: never zip the same key twice
                String storageKey = key.toString();
                try {
                    byte[] bytes = attachments.fetchByStorageKey(storageKey);
                    zip.putNextEntry(new ZipEntry("attachments/" + storageKey));
                    zip.write(bytes);
                    zip.closeEntry();
                    bundled++;
                } catch (Exception e) {
                    missing.add(storageKey);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the export bundle", e);
        }

        return new ExportBundle(buffer.toByteArray(), attachmentVersionRows.size(), bundled, missing);
    }

    /** VYB-0732 AC1 in miniature — counts only, for a quick "what would this export contain" preview. */
    public Map<String, Long> summary() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : tablesToExport()) {
            counts.put(table, jdbc.queryForObject("SELECT count(*) FROM " + quoted(table), Long.class));
        }
        return counts;
    }
}
