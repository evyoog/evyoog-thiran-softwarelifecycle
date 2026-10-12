package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vyoog.requirements.RequirementStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0941 (F18): the revision and status history of a requirement, over HTTP against a real PostgreSQL. The history is the
 * immutable {@code requirement_revision} rows plus the {@code requirement.transitioned} audit events, merged, newest first.
 */
@AutoConfigureMockMvc
class RequirementHistoryIT extends IntegrationTestBase {

    @Autowired MockMvc mvc;

    private JwtRequestPostProcessor signedIn() {
        String id = unique("reader");
        users.upsert("sub-" + id, id + "@it.test", id);
        return jwt().jwt(j -> j.subject("sub-" + id).claim("email", id + "@it.test").claim("preferred_username", id).claim("azp", "vyoog-web"));
    }

    @Test
    void VYB0941_AC1_theTimelineHoldsEveryRevisionAndEveryStatusChangeNewestFirstWithTheWholeSnapshot() throws Exception {
        Portfolio p = newPortfolio();
        UUID author = newUser("Ada Author");
        var r = newRequirement(p, author);
        var v2 = requirementService.update(r.getId(), 1, r.getTitle(), "The system shall answer within 2 seconds.", "FUNCTIONAL", "MEDIUM", p.capabilityId(), author);
        var v3 = requirementService.update(r.getId(), 2, "A clearer title", v2.getStatement(), "NON_FUNCTIONAL", "HIGH", p.capabilityId(), author);
        requirementService.transition(r.getId(), 3, RequirementStatus.IN_REVIEW, "no criteria yet, submitted on purpose", author);

        mvc.perform(get("/api/v1/requirements/" + r.getId() + "/history").with(signedIn())).andExpect(status().isOk())
            .andExpect(jsonPath("$.requirementId").value(r.getId().toString()))
            .andExpect(jsonPath("$.currentRevision").value(3)).andExpect(jsonPath("$.currentStatus").value("IN_REVIEW"))
            .andExpect(jsonPath("$.statusHistoryMayBeIncomplete").value(false))
            .andExpect(jsonPath("$.entries.length()").value(4))
            .andExpect(jsonPath("$.entries[0].kind").value("STATUS_CHANGE")).andExpect(jsonPath("$.entries[0].fromStatus").value("DRAFT"))
            .andExpect(jsonPath("$.entries[0].toStatus").value("IN_REVIEW")).andExpect(jsonPath("$.entries[0].reason").value("no criteria yet, submitted on purpose"))
            .andExpect(jsonPath("$.entries[1].kind").value("REVISION")).andExpect(jsonPath("$.entries[1].revision").value(3))
            .andExpect(jsonPath("$.entries[1].title").value("A clearer title")).andExpect(jsonPath("$.entries[1].type").value("NON_FUNCTIONAL"))
            .andExpect(jsonPath("$.entries[1].priority").value("HIGH")).andExpect(jsonPath("$.entries[1].statement").value(v3.getStatement()))
            .andExpect(jsonPath("$.entries[1].statusAtRevision").value("DRAFT"))
            .andExpect(jsonPath("$.entries[2].revision").value(2)).andExpect(jsonPath("$.entries[2].statement").value("The system shall answer within 2 seconds."))
            .andExpect(jsonPath("$.entries[3].revision").value(1)).andExpect(jsonPath("$.entries[3].statement").value(r.getStatement()));
    }

    @Test
    void VYB0941_AC2_eachRowNamesWhoDidItAndTheCapabilityTheRequirementWasIn() throws Exception {
        Portfolio p = newPortfolio();
        UUID author = newUser("Grace Writer");
        String authorName = jdbc.queryForObject("SELECT display_name FROM app_user WHERE id = ?", String.class, author);
        var r = newRequirement(p, author);
        requirementService.transition(r.getId(), 1, RequirementStatus.IN_REVIEW, "submitted on purpose, no criteria", author);
        String capabilityName = jdbc.queryForObject("SELECT name FROM capability WHERE id = ?", String.class, p.capabilityId());

        mvc.perform(get("/api/v1/requirements/" + r.getId() + "/history").with(signedIn()))
            .andExpect(jsonPath("$.entries[0].actorName").value(authorName)).andExpect(jsonPath("$.entries[0].actorId").value(author.toString()))
            .andExpect(jsonPath("$.entries[1].actorName").value(authorName))
            .andExpect(jsonPath("$.entries[1].capabilityId").value(p.capabilityId().toString()))
            .andExpect(jsonPath("$.entries[1].capabilityName").value(capabilityName));
    }

    @Test
    void VYB0941_AC3_aSaveThatChangesNothingMakesNoRevisionSoItIsNotInTheHistory() throws Exception {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        var r = newRequirement(p, author);

        requirementService.update(r.getId(), 1, r.getTitle(), r.getStatement(), r.getType(), r.getPriority(), p.capabilityId(), author);

        mvc.perform(get("/api/v1/requirements/" + r.getId() + "/history").with(signedIn()))
            .andExpect(jsonPath("$.entries.length()").value(1)).andExpect(jsonPath("$.currentRevision").value(1));
    }

    @Test
    void VYB0941_AC4_aRejectionKeepsItsReasonAndAStatusChangeIsNotARevision() throws Exception {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        UUID admin = newAdministrator();
        var r = newRequirement(p, author);
        requirementService.transition(r.getId(), 1, RequirementStatus.IN_REVIEW, "submitted on purpose, no criteria", author);
        requirementService.transition(r.getId(), 1, RequirementStatus.REVIEWED, null, admin);
        requirementService.transition(r.getId(), 1, RequirementStatus.REJECTED, "Out of scope for this release", admin);

        mvc.perform(get("/api/v1/requirements/" + r.getId() + "/history").with(signedIn()))
            .andExpect(jsonPath("$.currentStatus").value("REJECTED")).andExpect(jsonPath("$.currentRevision").value(1))
            .andExpect(jsonPath("$.entries.length()").value(4))
            .andExpect(jsonPath("$.entries[0].fromStatus").value("REVIEWED")).andExpect(jsonPath("$.entries[0].toStatus").value("REJECTED"))
            .andExpect(jsonPath("$.entries[0].reason").value("Out of scope for this release"))
            .andExpect(jsonPath("$.entries[1].toStatus").value("REVIEWED")).andExpect(jsonPath("$.entries[2].toStatus").value("IN_REVIEW"))
            .andExpect(jsonPath("$.entries[3].kind").value("REVISION"));
    }

    @Test
    void VYB0941_AC5_aRequirementWhoseCreationIsNotOnTheAuditLogSaysItsStatusHistoryMayBeIncomplete() throws Exception {
        Portfolio p = newPortfolio();
        UUID id = jdbc.queryForObject("""
            INSERT INTO requirement (key, capability_id, type, status, title, statement, revision)
            VALUES (?, ?, 'FUNCTIONAL', 'DRAFT', 'Older requirement', 'The system shall exist.', 1) RETURNING id
            """, UUID.class, unique("VY"), p.capabilityId());

        mvc.perform(get("/api/v1/requirements/" + id + "/history").with(signedIn())).andExpect(status().isOk())
            .andExpect(jsonPath("$.statusHistoryMayBeIncomplete").value(true)).andExpect(jsonPath("$.entries.length()").value(0));
    }

    @Test
    void VYB0941_AC6_anUnknownRequirementIsA404AndAnAnonymousCallerIsA401() throws Exception {
        mvc.perform(get("/api/v1/requirements/" + UUID.randomUUID() + "/history").with(signedIn())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/requirements/" + UUID.randomUUID() + "/history")).andExpect(status().isUnauthorized());
    }

    @Test
    void VYB0941_AC7_theHistoryIsReadOnlyAndTheRevisionsItShowsAreTheOnesTheDatabaseKeeps() throws Exception {
        Portfolio p = newPortfolio();
        UUID author = newUser("author");
        var r = newRequirement(p, author);
        requirementService.update(r.getId(), 1, r.getTitle(), "The system shall be revised once.", "FUNCTIONAL", "MEDIUM", p.capabilityId(), author);

        var result = mvc.perform(get("/api/v1/requirements/" + r.getId() + "/history").with(signedIn())).andReturn();

        assertThat(revisionRows(r.getId())).isEqualTo(2);
        assertThat(result.getResponse().getContentAsString()).contains("The system shall be revised once.");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/requirements/" + r.getId() + "/history").with(signedIn()).with(
            org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
            .andExpect(status().is4xxClientError());
    }
}
