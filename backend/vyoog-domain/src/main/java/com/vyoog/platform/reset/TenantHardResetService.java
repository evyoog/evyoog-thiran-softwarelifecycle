package com.vyoog.platform.reset;

import com.vyoog.platform.audit.AuditService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0733, reframed honestly: docs/DECISIONS.md D3 already recorded that "tenant
 * lifecycle" (provisioning/suspension/export/deletion of *a* tenant, VYB-0730–0734)
 * doesn't apply to this single-tenant, schema-per-app deployment — there is exactly
 * one tenant, and it isn't provisioned or deleted through an in-app endpoint. What
 * this builds instead is the real substance behind "hard delete, no orphans": a
 * genuine, irreversible wipe of every row this application owns — an operational
 * reset tool, not multi-tenant deletion, and named for what it actually is.
 *
 * <p>Two tables are deliberately exempt from the wipe:
 * <ul>
 *   <li>{@code audit_event} — append-only by its own DB trigger (V001), and the
 *       record of the reset itself gets written here immediately after. Wiping the
 *       audit trail as part of an audited action would erase the evidence that the
 *       action happened.</li>
 *   <li>{@code app_config} — the one singleton settings row this deployment has;
 *       there's nothing to "orphan" by keeping it, and deleting it would break every
 *       service that assumes {@code WHERE id = 1} exists ({@code AppConfigService},
 *       {@code SuspensionFilter}, ...). Its {@code bootstrapped_at} column IS reset
 *       to {@code NULL}, though — leaving it set after every {@code app_user}/
 *       {@code access_grant} row is gone would permanently lock out ever bootstrapping
 *       a first administrator again (see {@code TenantBootstrapService}), which would
 *       make the reset actively harmful rather than a clean slate.</li>
 * </ul>
 *
 * <p>Every other table is discovered from {@code information_schema} at call time,
 * not hand-copied from {@code TenantExportService.TABLES} — that list has already
 * drifted (missing {@code team}/{@code team_member}/{@code document_requirement}/
 * {@code change_request_requirement}/{@code ingested_commit}/{@code review_comment}
 * as of session 14), which is exactly the failure mode a "no orphans" guarantee can't
 * afford. {@code TRUNCATE ... CASCADE} across the whole set in one statement lets
 * Postgres itself resolve the FK-safe order — safer than a hand-maintained delete
 * sequence for the same reason the discovery query is: one wrong hand-derived order
 * is exactly how an orphan gets left behind.
 */
@Service
public class TenantHardResetService {

    private static final List<String> EXEMPT = List.of("app_config", "audit_event", "flyway_schema_history");

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public TenantHardResetService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** A dry-run preview: exactly what would be wiped and how many rows, before anyone commits to it. */
    public record ResetPreview(List<TableCount> tables, long totalRows) {}
    public record TableCount(String table, long rowCount) {}

    public ResetPreview preview() {
        List<TableCount> counts = tablesToWipe().stream()
            .map(t -> new TableCount(t, count(t)))
            .toList();
        return new ResetPreview(counts, counts.stream().mapToLong(TableCount::rowCount).sum());
    }

    /**
     * Irreversible. The caller (the controller) is responsible for the confirmation
     * gate — this method does the wipe the moment it's called, no second thoughts here.
     */
    @Transactional
    public void reset(UUID actorId, String actorEmail) {
        List<String> tables = tablesToWipe();
        if (!tables.isEmpty()) {
            jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
        }
        // VYB-0730: without this, no app_user/access_grant row survives to satisfy
        // "already bootstrapped" — leaving it set would permanently block ever
        // granting a first administrator again.
        jdbc.update("UPDATE app_config SET bootstrapped_at = NULL WHERE id = 1");

        audit.record(actorId, "tenant.hard-reset", "APP_CONFIG", null, null,
            Map.of("tablesWiped", tables, "requestedBy", actorEmail == null ? "" : actorEmail));
    }

    private List<String> tablesToWipe() {
        List<String> allBaseTables = jdbc.queryForList("""
            SELECT table_name FROM information_schema.tables
            WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """, String.class);
        return allBaseTables.stream().filter(t -> !EXEMPT.contains(t)).toList();
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }
}
