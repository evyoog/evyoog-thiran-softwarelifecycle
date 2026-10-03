package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vyoog.platform.export.TenantExportService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * VYB-0911 (F23): the export is meant to be a complete accounting of the application's data, but it
 * read from a hand-written list of table names that nobody updated as migrations added tables, so it
 * silently left out saved views, teams, review comments, task dismissals and more. The list now comes
 * from the schema itself, minus a short, named list of tables that are not data.
 */
class TenantExportIT extends IntegrationTestBase {

    @Autowired TenantExportService export;

    /** Every ordinary table in the schema that is not a partition of another one. */
    private Set<String> tablesInSchema() {
        return new TreeSet<>(jdbc.queryForList("""
            SELECT c.relname FROM pg_class c
             WHERE c.relnamespace = current_schema()::regnamespace AND c.relkind IN ('r', 'p') AND NOT c.relispartition
            """, String.class));
    }

    private Set<String> exportedTables() {
        return new TreeSet<>(export.export().tables().stream().map(TenantExportService.TableDump::table).toList());
    }

    @Test
    void VYB0911_AC2_everyTableInTheSchemaIsExportedExceptTheNamedNonDataOnes() {
        Set<String> missing = tablesInSchema();
        missing.removeAll(exportedTables());
        missing.removeAll(TenantExportService.NOT_EXPORTED);
        missing.removeIf(t -> t.startsWith("audit_event_archive_"));

        assertThat(missing).as("tables in the schema that the export leaves out without saying so").isEmpty();
    }

    @Test
    void VYB0911_AC2_theTablesThatWereMissingAreNowThere() {
        assertThat(exportedTables()).contains("saved_view", "team", "team_member", "review_comment",
            "task_completion", "import_document_analysis", "ingested_commit", "brief_capability",
            "change_request_requirement", "document_requirement");
    }

    @Test
    void VYB0911_AC2_bookkeepingThatIsNotDataIsLeftOutAndSaysSo() {
        assertThat(exportedTables()).doesNotContain("flyway_schema_history", "rate_limit_hit", "scheduler_lock");
        assertThat(TenantExportService.NOT_EXPORTED).containsExactlyInAnyOrder(
            "flyway_schema_history", "rate_limit_hit", "scheduler_lock");
    }

    @Test
    void VYB0911_AC2_aRowInAPreviouslyMissingTableIsInTheManifest() {
        UUID owner = newUser("exp");
        String name = unique("export-view");
        jdbc.update("INSERT INTO saved_view (owner_id, name, status) VALUES (?, ?, 'REVIEWED')", owner, name);

        List<Map<String, Object>> rows = export.export().tables().stream()
            .filter(t -> t.table().equals("saved_view")).findFirst().orElseThrow().rows();

        assertThat(rows).anySatisfy(r -> assertThat(r.get("name")).isEqualTo(name));
    }

    @Test
    void VYB0911_AC2_theSummaryCountsTheSameTablesTheExportDumps() {
        assertThat(export.summary().keySet()).containsExactlyInAnyOrderElementsOf(exportedTables());
        assertThat(export.summary().values()).doesNotContain(-1L);
    }

    @Test
    void VYB0911_AC2_theManifestTotalIsTheSumOfItsTables() {
        TenantExportService.Manifest manifest = export.export();
        assertThat(manifest.totalRows()).isEqualTo(manifest.tables().stream().mapToLong(TenantExportService.TableDump::rowCount).sum());
    }
}
