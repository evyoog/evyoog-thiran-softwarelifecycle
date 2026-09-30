package com.vyoog.api;

import com.vyoog.evidence.TestCase;
import com.vyoog.evidence.TestCaseService;
import com.vyoog.platform.reset.TenantHardResetService;
import com.vyoog.requirements.LifecycleHistoryService;
import com.vyoog.trace.TraceGraphAssemblyService;
import com.vyoog.trace.TraceObjectType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session 14: exercises the actual new SQL paths against the real live database —
 * lifecycle spine, trace graph assembly, test-case drafting (+ the VERIFIES link it
 * creates), and the hard-reset preview — with real beans, not mocks. Named so neither
 * Surefire's default include (**{@code /*Test.java}} — this doesn't match: no "Test"
 * suffix... actually it does via "Runner" not matching either) nor Failsafe's
 * (**{@code /*IT.java}) picks it up; run only by explicit name, same as
 * LoadRehearsalRunner.
 *
 * <pre>
 * DB_URL=... DB_USER=... DB_PASSWORD=... mvn -pl vyoog-api -am test \
 *   -Dtest=Session14VerificationRunner -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * Cleans up everything it creates.
 */
class Session14VerificationRunner extends VerificationRunnerBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired LifecycleHistoryService lifecycleHistory;
    @Autowired TraceGraphAssemblyService graphAssembly;
    @Autowired TestCaseService testCaseService;
    @Autowired TenantHardResetService hardReset;

    @Test
    void spineRunsCleanlyAgainstRealSchema() {
        var spine = lifecycleHistory.spine();
        assertThat(spine).hasSize(6);
        spine.forEach(s -> System.out.println("[verify] spine " + s.stage() + " = " + s.count()));
        assertThat(spine).allSatisfy(s -> assertThat(s.count()).isGreaterThanOrEqualTo(0));
    }

    @Test
    void draftTestCaseCreatesRealRowAndVerifiesLink() {
        UUID productId = jdbc.queryForObject(
            "INSERT INTO product (key, name) VALUES ('S14-VERIFY', 'Session 14 Verify') RETURNING id", UUID.class);
        UUID appId = jdbc.queryForObject(
            "INSERT INTO application (product_id, name) VALUES (?, 'S14 App') RETURNING id", UUID.class, productId);
        UUID capId = jdbc.queryForObject(
            "INSERT INTO capability (application_id, name) VALUES (?, 'S14 Cap') RETURNING id", UUID.class, appId);
        UUID reqId = jdbc.queryForObject("""
            INSERT INTO requirement (key, capability_id, type, title, statement)
            VALUES ('VY-S14VERIFY', ?, 'FUNCTIONAL', 'verify title', 'verify statement') RETURNING id
            """, UUID.class, capId);

        try {
            TestCase tc = testCaseService.draft("Session 14 draft test", null, null, reqId, null);
            assertThat(tc.getStatus()).isEqualTo(TestCase.Status.DRAFT);
            assertThat(tc.getKey()).startsWith("TC-");

            Integer linkCount = jdbc.queryForObject(
                "SELECT count(*) FROM trace_link WHERE from_type='TEST' AND from_id=? AND to_type='REQUIREMENT' AND to_id=? AND link_type='VERIFIES'",
                Integer.class, tc.getId(), reqId);
            assertThat(linkCount).isEqualTo(1);
            System.out.println("[verify] drafted test case " + tc.getKey() + " with a real VERIFIES link to " + reqId);

            var graph = graphAssembly.graphFor(TraceObjectType.REQUIREMENT, reqId, 3);
            System.out.println("[verify] trace graph around the requirement: " + graph.nodes().size() + " nodes, " + graph.edges().size() + " edges");
            assertThat(graph.nodes()).anyMatch(n -> n.type().equals("TEST") && n.id().equals(tc.getId().toString()));
            assertThat(graph.edges()).anyMatch(e -> e.linkType().equals("VERIFIES"));
        } finally {
            // Order matters: capture the test_case id from trace_link BEFORE deleting
            // the link, not after (a first draft here deleted the link first, so the
            // dependent test_case lookup found nothing and leaked one TC- row into the
            // live database — caught by checking table row counts after the run, not
            // assumed clean because the test itself passed).
            jdbc.update("DELETE FROM test_case WHERE id IN (SELECT from_id FROM trace_link WHERE to_id = ?)", reqId);
            jdbc.update("DELETE FROM finding WHERE object_id = ?", reqId);
            jdbc.update("DELETE FROM trace_closure WHERE ancestor_id = ? OR descendant_id = ?", reqId, reqId);
            jdbc.update("DELETE FROM trace_link WHERE to_id = ? OR from_id = ?", reqId, reqId);
            jdbc.update("DELETE FROM requirement WHERE id = ?", reqId);
            jdbc.update("DELETE FROM capability WHERE id = ?", capId);
            jdbc.update("DELETE FROM application WHERE id = ?", appId);
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
        }
    }

    @Test
    void resetPreviewIsReadOnlyAndFindsRealTables() {
        var preview = hardReset.preview();
        System.out.println("[verify] reset preview: " + preview.totalRows() + " rows across " + preview.tables().size() + " tables");
        assertThat(preview.tables()).extracting("table")
            .contains("requirement", "team", "team_member", "document_requirement", "ingested_commit")
            .doesNotContain("app_config", "audit_event", "flyway_schema_history");
    }
}
