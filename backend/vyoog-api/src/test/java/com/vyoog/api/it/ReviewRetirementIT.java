package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vyoog.requirements.LifecycleHistoryService;
import com.vyoog.requirements.Requirement;
import com.vyoog.tasks.TaskKind;
import com.vyoog.tasks.TaskService;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0944 (F17, D26): review rounds are retired. The endpoints, service and My Work tasks are gone; the round tables stay
 * as read-only history, and a requirement's REVIEW and APPROVAL stages now come from its status changes (or, on an old
 * requirement, from the old round's signature). Against a real PostgreSQL.
 */
@AutoConfigureMockMvc
class ReviewRetirementIT extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired LifecycleHistoryService lifecycle;
    @Autowired TaskService tasks;

    private Portfolio p;
    private UUID author, admin;

    @BeforeEach
    void fixtures() {
        p = newPortfolio();
        author = newUser("author");
        admin = newAdministrator();
    }

    private UUID oldRound(Requirement r, UUID participant, String role, boolean signed) {
        UUID review = jdbc.queryForObject("INSERT INTO review (title, state) VALUES (?, 'OPEN') RETURNING id", UUID.class, unique("Old round"));
        jdbc.update("INSERT INTO review_item (review_id, requirement_id, revision) VALUES (?, ?, ?)", review, r.getId(), r.getRevision());
        jdbc.update("INSERT INTO review_participant (review_id, user_id, role, signed_at) VALUES (?, ?, ?, ?)", review, participant, role,
            signed ? java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3600)) : null);
        return review;
    }

    private long spineCount(String stage) {
        return lifecycle.spine().stream().filter(s -> s.stage().equals(stage)).findFirst().orElseThrow().count();
    }

    @Test
    void VYB0944_AC1_theReviewEndpointsAreGone() throws Exception {
        var token = jwt().jwt(j -> j.subject("sub-x").claim("email", "x@it.test").claim("preferred_username", "x").claim("azp", "vyoog-web"));
        mvc.perform(get("/api/v1/reviews").with(token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/reviews/" + UUID.randomUUID()).with(token)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/reviews").with(token).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/reviews/" + UUID.randomUUID() + "/sign").with(token)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/reviews/" + UUID.randomUUID() + "/close").with(token)).andExpect(status().isNotFound());
    }

    @Test
    void VYB0944_AC2_theRoundTablesAreKeptAndStillReadable() {
        Requirement r = newRequirement(p, author);
        UUID review = oldRound(r, admin, "REVIEWER", true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_participant WHERE review_id = ? AND signed_at IS NOT NULL", Integer.class, review))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_item WHERE review_id = ?", Integer.class, review)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = 'review_comment'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void VYB0944_AC3_aRequirementGetsItsReviewAndApprovalStagesFromItsStatusChanges() {
        Requirement r = approved(newRequirement(p, author), author, admin);

        var stages = lifecycle.historyFor(r.getId());

        assertThat(stages).extracting(LifecycleHistoryService.Stage::stage).contains("AUTHORING", "REVIEW", "APPROVAL");
        assertThat(stages).filteredOn(s -> s.stage().equals("REVIEW")).singleElement().satisfies(s -> assertThat(s.actorId()).isEqualTo(admin));
        assertThat(stages).filteredOn(s -> s.stage().equals("APPROVAL")).singleElement().satisfies(s -> assertThat(s.actorId()).isEqualTo(admin));
    }

    @Test
    void VYB0944_AC4_aRequirementNotYetReviewedHasNeitherStage() {
        Requirement r = newRequirement(p, author);
        assertThat(lifecycle.historyFor(r.getId())).extracting(LifecycleHistoryService.Stage::stage).containsExactly("AUTHORING");
    }

    @Test
    void VYB0944_AC5_aRequirementThatWentThroughAnOldRoundKeepsItsStages() {
        Requirement r = newRequirement(p, author);              // still a draft: only the old signatures say it was reviewed and approved
        oldRound(r, admin, "REVIEWER", true);
        oldRound(r, newUser("approver"), "APPROVER", true);

        assertThat(lifecycle.historyFor(r.getId())).extracting(LifecycleHistoryService.Stage::stage).contains("REVIEW", "APPROVAL");
    }

    @Test
    void VYB0944_AC6_theSpineCountsBothWaysAndCountsARequirementOnce() {
        long reviewBefore = spineCount("REVIEW"), approvalBefore = spineCount("APPROVAL");

        Requirement viaStatus = approved(newRequirement(p, author), author, admin);          // status changes, and nothing else
        Requirement viaRound = newRequirement(p, author);                                    // old signatures, and nothing else
        oldRound(viaRound, admin, "REVIEWER", true);
        oldRound(viaRound, newUser("approver"), "APPROVER", true);
        oldRound(viaStatus, admin, "REVIEWER", true);                                         // both ways at once: still one

        assertThat(spineCount("REVIEW")).isEqualTo(reviewBefore + 2);
        assertThat(spineCount("APPROVAL")).isEqualTo(approvalBefore + 2);
    }

    @Test
    void VYB0944_AC7_anOpenOldRoundNoLongerCreatesAnyTaskAndTheTwoKindsAreGone() {
        Requirement r = newRequirement(p, author);
        UUID reviewer = newUser("reviewer");
        UUID approver = newUser("approver");
        oldRound(r, reviewer, "REVIEWER", false);
        oldRound(r, approver, "APPROVER", false);

        assertThat(tasks.tasksFor(reviewer)).isEmpty();
        assertThat(tasks.tasksFor(approver)).isEmpty();
        assertThat(Arrays.stream(TaskKind.values()).map(Enum::name).toList())
            .containsExactlyInAnyOrder("AUTHOR_WORDING", "DEVELOPER_IMPLEMENT", "DEVELOPER_REIMPLEMENT", "TESTER_VERIFY", "TESTER_REVERIFY");
    }

    @Test
    void VYB0944_AC8_aDismissalOfAnOldRoundTaskIsHistoryAndBreaksNothing() {
        Requirement r = newRequirement(p, author);
        UUID reviewer = newUser("reviewer");
        UUID review = oldRound(r, reviewer, "REVIEWER", false);
        jdbc.update("INSERT INTO task_completion (kind, object_id, object_revision, user_id) VALUES ('REVIEWER_PENDING', ?, NULL, ?)", review, reviewer);

        assertThat(tasks.tasksFor(reviewer)).isEmpty();
        assertThat(tasks.completedToday(reviewer)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_completion WHERE user_id = ?", Integer.class, reviewer)).isEqualTo(1);
    }

    @Test
    void VYB0944_AC9_theSodRuleIsRetiredItsOpenFindingsResolvedAndItsHistoryKept() {
        assertThat(jdbc.queryForObject("SELECT retired_at IS NOT NULL FROM gap_rule_template WHERE key = 'sod'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM finding WHERE rule_key = 'sod' AND state = 'OPEN'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'rule.retired' AND after->>'key' = 'sod'", Integer.class))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gap_rule_template WHERE retired_at IS NULL", Integer.class)).isPositive();
    }

    @Test
    void VYB0944_AC10_theRulesScreenNoLongerListsTheRetiredRule() throws Exception {
        var token = jwt().jwt(j -> j.subject("sub-x").claim("email", "x@it.test").claim("preferred_username", "x").claim("azp", "vyoog-web"));
        mvc.perform(get("/api/v1/rules").with(token)).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.key == 'sod')]").isEmpty())
            .andExpect(jsonPath("$[?(@.key == 'noverify')]").isNotEmpty());
    }
}
