package com.vyoog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vyoog.brief.BriefSection;
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
 * VYB-0831: {@link BriefService}'s "only requirements that already have a test case are
 * briefed" gate, end to end against the real, live database this sandbox actually has —
 * deliberately no AI call (that path is already covered, separately and at real billed
 * cost, by {@link BriefElaborationVerificationRunner}). Same explicit-name-only
 * convention as every other {@code *VerificationRunner} — invisible to {@code mvn test}/
 * {@code mvn verify} — and the same reason this one isn't named starting with the word
 * "Test": Surefire's default include pattern picks up any file whose name starts with
 * that word, and would run it anyway (the mistake caught and fixed in VYB-0824).
 *
 * <pre>
 * source .env && mvn -pl vyoog-api -am test -Dtest=BriefTestCaseGateVerificationRunner \
 *   -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@SpringBootTest
class BriefTestCaseGateVerificationRunner {

    @Autowired JdbcTemplate jdbc;
    @Autowired BriefService briefService;
    @Autowired TestCaseService testCaseService;

    @Test
    void onlyTheApprovedRequirementWithATestCaseIsBriefedAndItsTestCasesRenderInline() {
        UUID productId = jdbc.queryForObject(
            "INSERT INTO product (key, name) VALUES ('VYB0831-BRIEF', 'VYB-0831 Brief') RETURNING id", UUID.class);
        UUID appId = jdbc.queryForObject(
            "INSERT INTO application (product_id, name) VALUES (?, 'VYB-0831 App') RETURNING id", UUID.class, productId);
        UUID capId = jdbc.queryForObject(
            "INSERT INTO capability (application_id, name) VALUES (?, 'VYB-0831 Cap') RETURNING id", UUID.class, appId);
        UUID devId = jdbc.queryForObject("""
            INSERT INTO app_user (subject, email, display_name)
            VALUES ('vyb0831-dev-sub', 'dev@vyb0831.test', 'VYB-0831 Dev') RETURNING id
            """, UUID.class);

        UUID ready = jdbc.queryForObject("""
            INSERT INTO requirement (key, capability_id, type, status, title, statement, revision)
            VALUES ('VY-0831-READY', ?, 'FUNCTIONAL', 'APPROVED', 'Ready requirement',
                    'The system shall do the ready thing.', 1) RETURNING id
            """, UUID.class, capId);
        UUID notReady = jdbc.queryForObject("""
            INSERT INTO requirement (key, capability_id, type, status, title, statement, revision)
            VALUES ('VY-0831-NOTREADY', ?, 'FUNCTIONAL', 'APPROVED', 'Not-ready requirement',
                    'The system shall do the not-ready thing.', 1) RETURNING id
            """, UUID.class, capId);

        var testCase = testCaseService.draft("Does the ready thing", "- Do the thing\n- Expect it done",
            TestCase.Category.INDIVIDUAL, ready, devId);

        try {
            var brief = briefService.generate(appId, "VYB-0831 App", List.of(), BriefTarget.HUMAN, devId, devId,
                BriefSection.ALL, false);
            System.out.println("[VYB-0831 generated brief]\n" + brief.getContent());

            assertThat(brief.getContent()).contains("VY-0831-READY");
            assertThat(brief.getContent()).doesNotContain("VY-0831-NOTREADY");
            assertThat(brief.getContent()).contains("Does the ready thing");
            assertThat(brief.getContent()).contains("Do the thing").contains("Expect it done");
            assertThat(brief.getContent()).contains("1 approved with no test case yet");
        } finally {
            jdbc.update("DELETE FROM test_case WHERE id = ?", testCase.getId());
            jdbc.update("DELETE FROM trace_link WHERE to_id IN (?, ?)", ready, notReady);
            jdbc.update("DELETE FROM brief_requirement WHERE requirement_id IN (?, ?)", ready, notReady);
            jdbc.update("DELETE FROM brief WHERE application_id = ?", appId);
            jdbc.update("DELETE FROM requirement WHERE id IN (?, ?)", ready, notReady);
            jdbc.update("DELETE FROM capability WHERE id = ?", capId);
            jdbc.update("DELETE FROM application WHERE id = ?", appId);
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
            jdbc.update("DELETE FROM app_user WHERE id = ?", devId);
        }
    }

    @Test
    void anApprovedRequirementWithNoTestCaseAnywhereIsRefusedAgainstRealPostgres() {
        UUID productId = jdbc.queryForObject(
            "INSERT INTO product (key, name) VALUES ('VYB0831-REFUSE', 'VYB-0831 Refuse') RETURNING id", UUID.class);
        UUID appId = jdbc.queryForObject(
            "INSERT INTO application (product_id, name) VALUES (?, 'VYB-0831 Refuse App') RETURNING id", UUID.class, productId);
        UUID capId = jdbc.queryForObject(
            "INSERT INTO capability (application_id, name) VALUES (?, 'VYB-0831 Refuse Cap') RETURNING id", UUID.class, appId);
        UUID devId = jdbc.queryForObject("""
            INSERT INTO app_user (subject, email, display_name)
            VALUES ('vyb0831-refuse-dev-sub', 'dev@vyb0831refuse.test', 'VYB-0831 Refuse Dev') RETURNING id
            """, UUID.class);
        jdbc.update("""
            INSERT INTO requirement (key, capability_id, type, status, title, statement, revision)
            VALUES ('VY-0831-REFUSE', ?, 'FUNCTIONAL', 'APPROVED', 'No test case yet',
                    'The system shall do a thing nobody tested yet.', 1)
            """, capId);

        try {
            assertThatThrownBy(() -> briefService.generate(appId, "VYB-0831 Refuse App", List.of(),
                BriefTarget.HUMAN, devId, devId, BriefSection.ALL, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("None of the 1 approved requirements in scope have a test case yet");
        } finally {
            jdbc.update("DELETE FROM requirement WHERE capability_id = ?", capId);
            jdbc.update("DELETE FROM capability WHERE id = ?", capId);
            jdbc.update("DELETE FROM application WHERE id = ?", appId);
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
            jdbc.update("DELETE FROM app_user WHERE id = ?", devId);
        }
    }
}
