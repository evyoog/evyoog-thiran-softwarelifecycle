package com.vyoog.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vyoog.ai.RequirementElaborationAdvisor;
import com.vyoog.brief.BriefService;
import com.vyoog.brief.BriefTarget;
import com.vyoog.evidence.TestCase;
import com.vyoog.evidence.TestCaseService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * VYB-0817 against the real OpenAI API — same explicit-name-only convention as
 * PortfolioDashboardVerificationRunner/DesignPipelineMilestonesVerificationRunner,
 * invisible to {@code mvn test}/{@code mvn verify}. Requires AI_ENABLED=true and a real
 * AI_API_KEY sourced (this project's run-local.sh does that from .env) — a genuine,
 * small, billed call, run once deliberately rather than on every build.
 *
 * <pre>
 * source .env && mvn -pl vyoog-api -am test -Dtest=BriefElaborationVerificationRunner \
 *   -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@SpringBootTest
class BriefElaborationVerificationRunner {

    @Autowired JdbcTemplate jdbc;
    @Autowired RequirementElaborationAdvisor advisor;
    @Autowired BriefService briefService;
    @Autowired TestCaseService testCaseService;

    @Test
    void theRealOpenAiCallProducesAGroundedElaboration() {
        assertThat(advisor.available())
            .as("AI_ENABLED/AI_API_KEY must be set (source .env) for this to mean anything")
            .isTrue();

        var result = advisor.elaborate("Verification App", List.of(new RequirementElaborationAdvisor.Input(
            "VY-TEST", "Overtime alert", "The system shall alert a manager when an employee exceeds 12 hours of overtime in a week.",
            List.of("An alert is raised within 5 minutes of the 12-hour threshold being crossed",
                    "The alert names the employee and the hours worked"))));

        assertThat(result).hasSize(1);
        var elaboration = result.get(0);
        System.out.println("[VYB-0817 real OpenAI reply] " + elaboration.detail());
        assertThat(elaboration.isWellFormed()).isTrue();
        assertThat(elaboration.detail()).isNotBlank();
        // Grounding check: the real threshold from the input should survive into the
        // elaboration somewhere, proving this is an expansion of the given facts, not a
        // disconnected paragraph.
        assertThat(elaboration.detail()).contains("12");
    }

    @Test
    void endToEndThroughBriefServiceRendersTheElaborationInTheMarkdown() {
        UUID productId = jdbc.queryForObject(
            "INSERT INTO product (key, name) VALUES ('VYB0817', 'VYB-0817 Product') RETURNING id", UUID.class);
        UUID appId = jdbc.queryForObject(
            "INSERT INTO application (product_id, name) VALUES (?, 'VYB-0817 App') RETURNING id", UUID.class, productId);
        UUID capId = jdbc.queryForObject(
            "INSERT INTO capability (application_id, name) VALUES (?, 'VYB-0817 Cap') RETURNING id", UUID.class, appId);
        UUID devId = jdbc.queryForObject("""
            INSERT INTO app_user (subject, email, display_name)
            VALUES ('vyb0817-dev-sub', 'dev@vyb0817.test', 'VYB-0817 Dev') RETURNING id
            """, UUID.class);
        UUID reqId = jdbc.queryForObject("""
            INSERT INTO requirement (key, capability_id, type, status, title, statement, revision)
            VALUES ('VY-0817', ?, 'FUNCTIONAL', 'APPROVED', 'Overtime alert',
                    'The system shall alert a manager when an employee exceeds 12 hours of overtime in a week.', 1)
            RETURNING id
            """, UUID.class, capId);
        // VYB-0831: this requirement now also needs a test case to be briefed at all —
        // the gate this file's own name doesn't test, but has to satisfy to still prove
        // what it does test (AI elaboration rendering).
        var testCase = testCaseService.draft("Alerts within 5 minutes of the threshold", "- Simulate 12+ hours\n- Expect an alert",
            TestCase.Category.INDIVIDUAL, reqId, devId);

        try {
            var brief = briefService.generate(appId, "VYB-0817 App", List.of(), BriefTarget.HUMAN, devId, devId,
                com.vyoog.brief.BriefSection.ALL, true);
            System.out.println("[VYB-0817 generated brief]\n" + brief.getContent());
            assertThat(brief.getContent()).contains("AI elaboration");
            assertThat(brief.getContent()).contains("alert a manager when an employee exceeds 12 hours");
        } finally {
            // brief_requirement.requirement_id lacks ON DELETE CASCADE from this side (same
            // lesson as DesignPipelineMilestonesVerificationRunner's deployment_requirement),
            // so the brief itself must go before the requirement it references.
            jdbc.update("DELETE FROM test_case WHERE id = ?", testCase.getId());
            jdbc.update("DELETE FROM trace_link WHERE to_id = ?", reqId);
            jdbc.update("DELETE FROM brief_requirement WHERE requirement_id IN "
                + "(SELECT id FROM requirement WHERE capability_id = ?)", capId);
            jdbc.update("DELETE FROM brief WHERE application_id = ?", appId);
            jdbc.update("DELETE FROM requirement WHERE capability_id = ?", capId);
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
            jdbc.update("DELETE FROM app_user WHERE id = ?", devId);
        }
    }
}
