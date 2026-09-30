package com.vyoog.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vyoog.api.web.BriefController;
import com.vyoog.brief.BriefSection;
import com.vyoog.brief.BriefService;
import com.vyoog.brief.BriefTarget;
import com.vyoog.evidence.TestCase;
import com.vyoog.evidence.TestCaseService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * VYB-0836: {@code GET /briefs} with no {@code applicationId} — the Delivery screen's
 * history, shown even before a product/application is picked — against real Postgres.
 * No AI call needed. Same explicit-name-only convention as every other
 * {@code *VerificationRunner} — invisible to {@code mvn test}/{@code mvn verify}.
 *
 * <pre>
 * source .env && mvn -pl vyoog-api -am test -Dtest=BriefHistoryVerificationRunner \
 *   -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
class BriefHistoryVerificationRunner extends VerificationRunnerBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired BriefController briefController;
    @Autowired BriefService briefService;
    @Autowired TestCaseService testCaseService;

    @Test
    void historyWithNoApplicationIdSpansEveryApplicationAgainstRealPostgres() {
        UUID productId = jdbc.queryForObject(
            "INSERT INTO product (key, name) VALUES ('VYB0836', 'VYB-0836 Product') RETURNING id", UUID.class);
        UUID appAId = jdbc.queryForObject(
            "INSERT INTO application (product_id, name) VALUES (?, 'VYB-0836 App A') RETURNING id", UUID.class, productId);
        UUID appBId = jdbc.queryForObject(
            "INSERT INTO application (product_id, name) VALUES (?, 'VYB-0836 App B') RETURNING id", UUID.class, productId);
        UUID capAId = jdbc.queryForObject(
            "INSERT INTO capability (application_id, name) VALUES (?, 'Cap A') RETURNING id", UUID.class, appAId);
        UUID capBId = jdbc.queryForObject(
            "INSERT INTO capability (application_id, name) VALUES (?, 'Cap B') RETURNING id", UUID.class, appBId);
        UUID devId = jdbc.queryForObject("""
            INSERT INTO app_user (subject, email, display_name)
            VALUES ('vyb0836-dev-sub', 'dev@vyb0836.test', 'VYB-0836 Dev') RETURNING id
            """, UUID.class);

        UUID reqAId = jdbc.queryForObject("""
            INSERT INTO requirement (key, capability_id, type, status, title, statement, revision)
            VALUES ('VY-0836-A', ?, 'FUNCTIONAL', 'APPROVED', 'Req A', 'statement A', 1) RETURNING id
            """, UUID.class, capAId);
        UUID reqBId = jdbc.queryForObject("""
            INSERT INTO requirement (key, capability_id, type, status, title, statement, revision)
            VALUES ('VY-0836-B', ?, 'FUNCTIONAL', 'APPROVED', 'Req B', 'statement B', 1) RETURNING id
            """, UUID.class, capBId);
        var tcA = testCaseService.draft("Test A", "steps", TestCase.Category.INDIVIDUAL, reqAId, devId);
        var tcB = testCaseService.draft("Test B", "steps", TestCase.Category.INDIVIDUAL, reqBId, devId);

        var briefA = briefService.generate(appAId, "VYB-0836 App A", List.of(), BriefTarget.HUMAN, devId, devId, BriefSection.ALL, false);
        var briefB = briefService.generate(appBId, "VYB-0836 App B", List.of(), BriefTarget.HUMAN, devId, devId, BriefSection.ALL, false);

        try {
            var everything = briefController.history(null);
            System.out.println("[verify] history(null) -> "
                + everything.stream().map(v -> v.id() + "/" + v.applicationName()).toList());
            assertThat(everything).extracting(BriefController.BriefView::id)
                .contains(briefA.getId().toString(), briefB.getId().toString());
            assertThat(everything.stream().filter(v -> v.id().equals(briefA.getId().toString())).findFirst().orElseThrow().applicationName())
                .isEqualTo("VYB-0836 App A");
            assertThat(everything.stream().filter(v -> v.id().equals(briefB.getId().toString())).findFirst().orElseThrow().applicationName())
                .isEqualTo("VYB-0836 App B");

            var onlyA = briefController.history(appAId);
            System.out.println("[verify] history(appAId) -> " + onlyA.stream().map(BriefController.BriefView::id).toList());
            assertThat(onlyA).extracting(BriefController.BriefView::id)
                .contains(briefA.getId().toString())
                .doesNotContain(briefB.getId().toString());
        } finally {
            jdbc.update("DELETE FROM test_case WHERE id IN (?, ?)", tcA.getId(), tcB.getId());
            jdbc.update("DELETE FROM trace_link WHERE to_id IN (?, ?)", reqAId, reqBId);
            jdbc.update("DELETE FROM brief_requirement WHERE requirement_id IN (?, ?)", reqAId, reqBId);
            jdbc.update("DELETE FROM brief WHERE application_id IN (?, ?)", appAId, appBId);
            jdbc.update("DELETE FROM requirement WHERE id IN (?, ?)", reqAId, reqBId);
            jdbc.update("DELETE FROM capability WHERE id IN (?, ?)", capAId, capBId);
            jdbc.update("DELETE FROM application WHERE id IN (?, ?)", appAId, appBId);
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
            jdbc.update("DELETE FROM app_user WHERE id = ?", devId);
        }
    }
}
