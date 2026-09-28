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

    /** Every table Flyway has ever created for this application's own data — platform/audit/outbox tables included, so the manifest is a complete accounting. */
    private static final List<String> TABLES = List.of(
        "product", "application", "capability", "glossary_term", "glossary_term_usage",
        "requirement", "requirement_revision", "acceptance_criterion", "requirement_comment",
        "attachment", "attachment_version", "clarification", "idempotency_key",
        "trace_link", "trace_closure", "design_flow", "design_node", "design_edge", "design_node_requirement",
        "gap_rule_template", "gap_rule", "finding",
        "clause", "requirement_embedding",
        "review", "review_item", "review_participant", "test_case", "test_run", "verification",
        "defect", "brief", "brief_requirement",
        "release", "release_scope_item", "scope_movement", "baseline", "baseline_item",
        "variant", "variant_applicability", "environment", "deployment", "deployment_requirement",
        "document", "import_batch", "import_candidate", "change_request",
        "app_user", "access_grant", "service_account",
        "audit_event", "outbox_event", "notification", "integration_connection", "webhook_delivery",
        "app_config");

    public record TableDump(String table, long rowCount, List<Map<String, Object>> rows) {}
    public record Manifest(Instant generatedAt, List<TableDump> tables, long totalRows) {}

    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public Manifest export() {
        return buildManifest();
    }

    private Manifest buildManifest() {
        List<TableDump> dumps = new ArrayList<>();
        long total = 0;
        for (String table : TABLES) {
            List<Map<String, Object>> rows;
            try {
                rows = jdbc.queryForList("SELECT * FROM " + table);
            } catch (Exception e) {
                // A table that doesn't exist yet on an older schema shouldn't fail the whole export.
                rows = List.of();
            }
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
        for (String table : TABLES) {
            try {
                Long n = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
                counts.put(table, n == null ? 0 : n);
            } catch (Exception e) {
                counts.put(table, -1L); // -1: table doesn't exist on this schema version
            }
        }
        return counts;
    }
}
