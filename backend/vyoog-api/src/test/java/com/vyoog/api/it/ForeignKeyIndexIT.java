package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * VYB-0910 (F36): every foreign key in the schema has an index on its referencing columns, or is on
 * the short, reasoned list below. A new migration that adds a foreign key and no index fails here,
 * on a database that has every migration applied.
 *
 * <p>"Has an index" means an index whose leading columns are the foreign key's columns.
 */
class ForeignKeyIndexIT extends IntegrationTestBase {

    /**
     * Deliberately unindexed: "who did it" columns pointing at app_user. Nothing in the application
     * filters or joins on them, and app_user rows are never deleted (people are deactivated), so no
     * foreign-key check ever needs to find rows by them; an index would only slow every write.
     * Decided with the product owner in VYB-0910. To add to this list, give the reason.
     */
    private static final Set<String> AUDIT_STYLE = Set.of(
        "access_grant.granted_by", "attachment_version.uploaded_by", "baseline.frozen_by", "brief.generated_by",
        "change_request.decided_by", "change_request.raised_by", "clarification.answered_by",
        "clarification.escalated_to", "clarification.raised_by", "finding.actioned_by",
        "import_batch.uploaded_by", "import_document_analysis.decided_by", "requirement.changed_by",
        "requirement.updated_by", "requirement_comment.author_id", "requirement_revision.changed_by",
        "review_comment.author_id", "scope_movement.moved_by", "test_case.created_by",
        "test_plan.created_by", "test_run.created_by", "test_run_case.executed_by", "test_run_step.executed_by",
        "test_run_evidence.added_by", "release_transition.changed_by");

    private Set<String> unindexedForeignKeys() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT c.conrelid::regclass::text AS tbl,
                   (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                      FROM unnest(c.conkey) WITH ORDINALITY k(attnum, ord)
                      JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum) AS cols
              FROM pg_constraint c
             WHERE c.contype = 'f' AND c.connamespace = current_schema()::regnamespace
               AND NOT EXISTS (
                     SELECT 1 FROM pg_index i
                      WHERE i.indrelid = c.conrelid AND i.indisvalid
                        AND (i.indkey::int2[])[0:array_length(c.conkey, 1) - 1] = c.conkey)
            """);
        Set<String> out = new TreeSet<>();
        for (Map<String, Object> r : rows) {
            String table = r.get("tbl").toString().replaceFirst("^[^.]+\\.", "");
            out.add(table + "." + r.get("cols"));
        }
        return out;
    }

    @Test
    void VYB0910_AC1_everyForeignKeyIsIndexedExceptTheReasonedAuditStyleOnes() {
        assertThat(unindexedForeignKeys())
            .as("foreign keys with no index: add an index in a new migration, or list it in AUDIT_STYLE with the reason")
            .containsExactlyInAnyOrderElementsOf(AUDIT_STYLE);
    }

    @Test
    void VYB0910_AC1_theColumnsTheApplicationFiltersOnAreIndexed() {
        Set<String> unindexed = unindexedForeignKeys();
        assertThat(unindexed).doesNotContain(
            "requirement.created_by", "requirement.developer_id", "requirement.tester_id",
            "notification.user_id", "team_member.user_id", "review_participant.user_id",
            "requirement_comment.requirement_id", "attachment.requirement_id", "clarification.requirement_id",
            "clarification.assigned_to", "defect.requirement_id", "baseline_item.requirement_id");
    }

    @Test
    void VYB0910_AC2_thePurgeJobsDeleteByAgeThroughAnIndex() {
        Map<String, String> indexes = new TreeMap<>();
        jdbc.query("SELECT tablename, indexname FROM pg_indexes WHERE schemaname = current_schema() "
            + "AND indexname IN ('idempotency_key_created_at_idx', 'webhook_delivery_received_at_idx')",
            rs -> { indexes.put(rs.getString("tablename"), rs.getString("indexname")); });
        assertThat(indexes).containsEntry("idempotency_key", "idempotency_key_created_at_idx")
            .containsEntry("webhook_delivery", "webhook_delivery_received_at_idx");
    }
}
