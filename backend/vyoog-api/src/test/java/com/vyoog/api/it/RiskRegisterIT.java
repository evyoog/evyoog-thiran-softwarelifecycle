package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vyoog.identity.AccessRole;
import com.vyoog.identity.ScopeType;
import com.vyoog.release.ReleaseService;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementLinkedWorkService;
import com.vyoog.risk.RiskService;
import com.vyoog.risk.RiskService.Fields;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0949 (F41, D40): the risk register: scoring, status with reasons, links to requirements and releases, that closed risks are
 * history, and who may manage them. Against a real PostgreSQL, service calls and HTTP with real tokens.
 */
@AutoConfigureMockMvc
class RiskRegisterIT extends IntegrationTestBase {

    @Autowired RiskService risks;
    @Autowired ReleaseService releases;
    @Autowired RequirementLinkedWorkService linked;
    @Autowired MockMvc mvc;

    private Portfolio p;
    private UUID author, admin;

    @BeforeEach
    void fixtures() {
        p = newPortfolio();
        author = newUser("author");
        admin = newAdministrator();
    }

    private Fields fields(String title, int l, int i) {
        return new Fields(title, "Detail of " + title, l, i, null, null);
    }

    private UUID risk(String title, int l, int i) {
        return risks.create(fields(title + " " + unique("r"), l, i), admin);
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM risk WHERE id = ?", String.class, id);
    }

    // ------------------------------------------------------------------ the register

    @Test
    void VYB0949_AC1_aRiskIsStoredOpenWithItsOwnKeyAndAudited() {
        UUID owner = newUser("owner");
        UUID a = risks.create(new Fields("  Provider outage  ", "  Detail ", 4, 5, owner, " Failover "), admin);
        UUID b = risk("Second", 1, 1);

        var d = risks.detail(a);
        assertThat(d.row().title()).isEqualTo("Provider outage");
        assertThat(d.description()).isEqualTo("Detail");
        assertThat(d.mitigation()).isEqualTo("Failover");
        assertThat(d.row().status()).isEqualTo("OPEN");
        assertThat(d.row().ownerId()).isEqualTo(owner);
        assertThat(d.row().key()).startsWith("RSK-").isNotEqualTo(risks.detail(b).row().key());
        assertThat(auditCount(a, "risk.created")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT (after->>'rating')::int FROM audit_event WHERE object_id = ? AND action = 'risk.created'", Integer.class, a)).isEqualTo(20);
    }

    @Test
    void VYB0949_AC1_aRiskIsRefusedWithoutATitleOrWithALevelOutsideOneToFiveOrAnUnknownOwner() {
        assertThatThrownBy(() -> risks.create(fields(" ", 3, 3), admin)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("title");
        assertThatThrownBy(() -> risks.create(fields("x".repeat(201), 3, 3), admin)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> risks.create(fields("t", 0, 3), admin)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Likelihood");
        assertThatThrownBy(() -> risks.create(fields("t", 3, 6), admin)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Impact");
        assertThatThrownBy(() -> risks.create(new Fields("t", null, 3, 3, UUID.randomUUID(), null), admin)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("person");
        assertThatThrownBy(() -> risks.create(new Fields("t", "x".repeat(4001), 3, 3, null, null), admin)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void VYB0949_AC2_theRatingIsLikelihoodTimesImpactTheBandIsReadFromItAndTheListIsHighestFirstWithClosedLast() {
        UUID low = risk("Low", 1, 4), med = risk("Med", 2, 3), high = risk("High", 3, 4), crit = risk("Crit", 4, 4), closedCrit = risk("ClosedCrit", 5, 5);
        risks.changeStatus(closedCrit, "CLOSED", "no longer applies", admin);

        var mine = risks.list().stream().filter(r -> List.of(low, med, high, crit, closedCrit).contains(r.id())).toList();

        assertThat(mine).extracting(r -> r.rating() + ":" + r.band()).containsExactly("16:CRITICAL", "12:HIGH", "6:MEDIUM", "4:LOW", "25:CRITICAL");
        assertThat(mine.get(4).id()).isEqualTo(closedCrit);
    }

    @Test
    void VYB0949_AC3_aRiskCanBeEditedTheOldValuesAreAuditedAndTheStatusIsUntouched() {
        UUID id = risk("Edit me", 2, 2);
        risks.changeStatus(id, "MITIGATING", null, admin);

        risks.update(id, new Fields("Edited title", "New", 5, 4, null, "Plan"), admin);

        var d = risks.detail(id);
        assertThat(d.row().title()).isEqualTo("Edited title");
        assertThat(d.row().rating()).isEqualTo(20);
        assertThat(d.mitigation()).isEqualTo("Plan");
        assertThat(d.row().status()).isEqualTo("MITIGATING");
        assertThat(jdbc.queryForObject("SELECT (before->>'rating')::int FROM audit_event WHERE object_id = ? AND action = 'risk.updated'", Integer.class, id)).isEqualTo(4);
        assertThatThrownBy(() -> risks.update(UUID.randomUUID(), fields("x", 1, 1), admin)).isInstanceOf(NoSuchElementException.class);
    }

    // ------------------------------------------------------------------ status

    @Test
    void VYB0949_AC4_acceptingOrClosingNeedsAReasonWhichIsKeptAndReopeningClearsTheClosedDate() {
        UUID id = risk("Status", 3, 3);

        risks.changeStatus(id, "MITIGATING", null, admin);                                         // no reason needed
        assertThatThrownBy(() -> risks.changeStatus(id, "ACCEPTED", "  ", admin)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> risks.changeStatus(id, "CLOSED", null, admin)).isInstanceOf(IllegalArgumentException.class);
        assertThat(statusOf(id)).isEqualTo("MITIGATING");

        risks.changeStatus(id, "ACCEPTED", "The board accepted it.", admin);
        assertThat(risks.detail(id).statusReason()).isEqualTo("The board accepted it.");
        assertThat(risks.detail(id).row().closedAt()).isNull();

        risks.changeStatus(id, "CLOSED", "Superseded by the new design.", admin);
        assertThat(risks.detail(id).row().closedAt()).isNotNull();

        risks.changeStatus(id, "OPEN", null, admin);
        assertThat(risks.detail(id).row().closedAt()).isNull();
        assertThat(risks.detail(id).statusReason()).isNull();
        assertThat(auditCount(id, "risk.status_changed")).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
            SELECT after->>'reason' FROM audit_event WHERE object_id = ? AND action = 'risk.status_changed' AND after->>'status' = 'CLOSED'""", String.class, id))
            .isEqualTo("Superseded by the new design.");
    }

    @Test
    void VYB0949_AC4_aStatusThatIsUnknownOrTheSameAsNowIsRefused() {
        UUID id = risk("Same", 1, 1);
        assertThatThrownBy(() -> risks.changeStatus(id, "OPEN", null, admin)).isInstanceOf(IllegalStateException.class).hasMessageContaining("already");
        assertThatThrownBy(() -> risks.changeStatus(id, "DONE", null, admin)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> risks.changeStatus(id, null, null, admin)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> risks.changeStatus(id, "MITIGATING", "x".repeat(1001), admin)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> risks.changeStatus(UUID.randomUUID(), "MITIGATING", null, admin)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void VYB0949_AC5_aClosedRiskIsHistoryItIsNotEditedOrRelinkedUntilReopened() {
        UUID id = risk("Closed", 3, 3);
        Requirement r = newRequirement(p, author);
        UUID release = releases.create(unique("R")).getId();
        risks.linkRequirement(id, r.getId(), admin);
        risks.changeStatus(id, "CLOSED", "gone", admin);

        assertThatThrownBy(() -> risks.update(id, fields("x", 1, 1), admin)).isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        assertThatThrownBy(() -> risks.linkRequirement(id, newRequirement(p, author).getId(), admin)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> risks.unlinkRequirement(id, r.getId(), admin)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> risks.linkRelease(id, release, admin)).isInstanceOf(IllegalStateException.class);
        assertThat(risks.detail(id).requirementLinks()).hasSize(1);          // still there, as it was

        risks.changeStatus(id, "OPEN", null, admin);
        risks.linkRelease(id, release, admin);
        assertThat(risks.detail(id).releaseLinks()).hasSize(1);
    }

    // ------------------------------------------------------------------ links

    @Test
    void VYB0949_AC6_linkingARequirementIsIdempotentAuditedAndUnlinkingRemovesIt() {
        UUID id = risk("Links", 3, 3);
        Requirement r = newRequirement(p, author);

        risks.linkRequirement(id, r.getId(), admin);
        risks.linkRequirement(id, r.getId(), admin);
        assertThat(risks.detail(id).requirementLinks()).singleElement().satisfies(l -> {
            assertThat(l.key()).isEqualTo(r.getKey());
            assertThat(l.status()).isEqualTo("DRAFT");
        });
        assertThat(risks.detail(id).row().requirements()).isEqualTo(1);
        assertThat(auditCount(id, "risk.requirement_linked")).isEqualTo(1);

        risks.unlinkRequirement(id, r.getId(), admin);
        assertThat(risks.detail(id).requirementLinks()).isEmpty();
        assertThat(auditCount(id, "risk.requirement_unlinked")).isEqualTo(1);
        assertThatThrownBy(() -> risks.unlinkRequirement(id, r.getId(), admin)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void VYB0949_AC6_aMissingDeletedOrReplacedRequirementCannotBeLinked() {
        UUID id = risk("Bad links", 3, 3);
        Requirement gone = newRequirement(p, author), old = newRequirement(p, author), newer = newRequirement(p, author);
        jdbc.update("UPDATE requirement SET deleted_at = now() WHERE id = ?", gone.getId());
        jdbc.update("UPDATE requirement SET superseded_by_id = ? WHERE id = ?", newer.getId(), old.getId());

        assertThatThrownBy(() -> risks.linkRequirement(id, UUID.randomUUID(), admin)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> risks.linkRequirement(id, gone.getId(), admin)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> risks.linkRequirement(id, old.getId(), admin)).isInstanceOf(IllegalStateException.class).hasMessageContaining("replaced");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_requirement WHERE risk_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void VYB0949_AC7_aRequirementDeletedAfterBeingLinkedIsNotCountedOrListed() {
        UUID id = risk("Later deleted", 3, 3);
        Requirement r = newRequirement(p, author), keep = newRequirement(p, author);
        risks.linkRequirement(id, r.getId(), admin);
        risks.linkRequirement(id, keep.getId(), admin);
        jdbc.update("UPDATE requirement SET deleted_at = now() WHERE id = ?", r.getId());

        assertThat(risks.detail(id).row().requirements()).isEqualTo(1);
        assertThat(risks.detail(id).requirementLinks()).extracting(l -> l.key()).containsExactly(keep.getKey());
    }

    @Test
    void VYB0949_AC8_linkingAReleaseIsIdempotentAuditedAndAMissingReleaseIsRefused() {
        UUID id = risk("Release", 3, 3);
        UUID a = releases.create(unique("R-a")).getId(), b = releases.create(unique("R-b")).getId();

        risks.linkRelease(id, a, admin);
        risks.linkRelease(id, a, admin);
        risks.linkRelease(id, b, admin);
        assertThat(risks.detail(id).releaseLinks()).hasSize(2);
        assertThat(risks.detail(id).row().releases()).isEqualTo(2);
        assertThat(auditCount(id, "risk.release_linked")).isEqualTo(2);

        risks.unlinkRelease(id, a, admin);
        assertThat(risks.detail(id).releaseLinks()).singleElement().satisfies(l -> assertThat(l.id()).isEqualTo(b));
        assertThatThrownBy(() -> risks.unlinkRelease(id, a, admin)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> risks.linkRelease(id, UUID.randomUUID(), admin)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void VYB0949_AC9_aRequirementsLinkedWorkListsItsOpenRisksHighestFirstAndNotClosedOnes() {
        Requirement r = newRequirement(p, author);
        UUID low = risk("Low", 1, 2), crit = risk("Crit", 5, 5), closed = risk("Closed", 5, 4);
        for (UUID id : List.of(low, crit, closed)) risks.linkRequirement(id, r.getId(), admin);
        risks.changeStatus(closed, "CLOSED", "done", admin);

        var shown = linked.of(r.getId()).risks();

        assertThat(shown).extracting(x -> x.rating() + ":" + x.band() + ":" + x.status()).containsExactly("25:CRITICAL:OPEN", "2:LOW:OPEN");
        assertThat(shown).extracting(x -> x.id()).containsExactly(crit, low);
    }

    @Test
    void VYB0949_AC10_theDatabaseRefusesALevelOutsideOneToFiveAndAnUnknownStatus() {
        UUID id = risk("Constraints", 3, 3);
        assertThatThrownBy(() -> jdbc.update("UPDATE risk SET likelihood = 6 WHERE id = ?", id)).hasMessageContaining("risk_likelihood_check");
        assertThatThrownBy(() -> jdbc.update("UPDATE risk SET impact = 0 WHERE id = ?", id)).hasMessageContaining("risk_impact_check");
        assertThatThrownBy(() -> jdbc.update("UPDATE risk SET status = 'DONE' WHERE id = ?", id)).hasMessageContaining("risk_status_check");
        assertThatThrownBy(() -> jdbc.update("UPDATE risk SET key = (SELECT key FROM risk WHERE id <> ? LIMIT 1) WHERE id = ?", id, id)).hasMessageContaining("risk_key_key");
    }

    // ------------------------------------------------------------------ HTTP

    private JwtRequestPostProcessor aPersonWith(AccessRole role, boolean platform) {
        String id = unique("p");
        UUID user = users.upsert("sub-" + id, id + "@it.test", id).getId();
        if (role != null && platform) grants.grant(user, role, ScopeType.PLATFORM, null, null, user);
        else if (role != null) grantOnCapability(user, role, p.capabilityId());
        return jwt().jwt(j -> j.subject("sub-" + id).claim("email", id + "@it.test").claim("preferred_username", id).claim("azp", "vyoog-web"));
    }

    @Test
    void VYB0949_AC11_whoMayManageRisksOverHttpApproverComplianceLeadArchitectAdministratorNotReviewerAnalystViewerOrNoOneAndAnyoneReads() throws Exception {
        UUID id = risk("Http", 3, 3);
        Requirement r = newRequirement(p, author);
        UUID release = releases.create(unique("R")).getId();
        String json = MediaType.APPLICATION_JSON_VALUE;
        String body = "{\"title\":\"Over HTTP\",\"likelihood\":4,\"impact\":4}";

        for (JwtRequestPostProcessor denied : List.of(aPersonWith(AccessRole.REVIEWER, false), aPersonWith(AccessRole.BUSINESS_ANALYST, false),
            aPersonWith(AccessRole.VIEWER, false), aPersonWith(null, false))) {
            mvc.perform(post("/api/v1/risks").with(denied).contentType(json).content(body)).andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/risks/" + id).with(denied).contentType(json).content(body)).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/risks/" + id + "/status").with(denied).contentType(json).content("{\"status\":\"MITIGATING\"}")).andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/risks/" + id + "/requirements/" + r.getId()).with(denied)).andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/risks/" + id + "/requirements/" + r.getId()).with(denied)).andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/risks/" + id + "/releases/" + release).with(denied)).andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/risks/" + id + "/releases/" + release).with(denied)).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/risks").contentType(json).content(body)).andExpect(status().isUnauthorized());
        assertThat(risks.detail(id).row().title()).startsWith("Http");

        for (JwtRequestPostProcessor allowed : List.of(aPersonWith(AccessRole.APPROVER, false), aPersonWith(AccessRole.COMPLIANCE_LEAD, false),
            aPersonWith(AccessRole.ARCHITECT, false), aPersonWith(AccessRole.ADMINISTRATOR, true))) {
            mvc.perform(post("/api/v1/risks").with(allowed).contentType(json).content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNotEmpty());
        }
        JwtRequestPostProcessor lead = aPersonWith(AccessRole.COMPLIANCE_LEAD, false);
        mvc.perform(put("/api/v1/risks/" + id).with(lead).contentType(json).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.risk.rating").value(16))
            .andExpect(jsonPath("$.risk.band").value("CRITICAL"));
        mvc.perform(post("/api/v1/risks/" + id + "/status").with(lead).contentType(json).content("{\"status\":\"ACCEPTED\",\"reason\":\"Board decision\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.risk.status").value("ACCEPTED")).andExpect(jsonPath("$.statusReason").value("Board decision"));
        mvc.perform(put("/api/v1/risks/" + id + "/requirements/" + r.getId()).with(lead)).andExpect(status().isOk());
        mvc.perform(put("/api/v1/risks/" + id + "/releases/" + release).with(lead)).andExpect(status().isOk());

        JwtRequestPostProcessor viewer = aPersonWith(AccessRole.VIEWER, false);
        mvc.perform(get("/api/v1/risks").with(viewer)).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == '" + id + "')].requirements").value(1));
        mvc.perform(get("/api/v1/risks/" + id).with(viewer)).andExpect(status().isOk()).andExpect(jsonPath("$.requirementLinks[0].key").value(r.getKey()))
            .andExpect(jsonPath("$.releaseLinks[0].id").value(release.toString()));
        mvc.perform(get("/api/v1/risks")).andExpect(status().isUnauthorized());

        mvc.perform(delete("/api/v1/risks/" + id + "/requirements/" + r.getId()).with(lead)).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/risks/" + id + "/requirements/" + r.getId()).with(lead)).andExpect(status().isNotFound());
    }

    @Test
    void VYB0949_AC12_aRiskIsNeverDeletedAndBadRequestsAreRefusedWithTheirReason() throws Exception {
        UUID id = risk("Never deleted", 3, 3);
        JwtRequestPostProcessor lead = aPersonWith(AccessRole.COMPLIANCE_LEAD, false);
        String json = MediaType.APPLICATION_JSON_VALUE;

        mvc.perform(delete("/api/v1/risks/" + id).with(lead)).andExpect(status().isMethodNotAllowed());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk WHERE id = ?", Integer.class, id)).isEqualTo(1);

        mvc.perform(post("/api/v1/risks").with(lead).contentType(json).content("{\"title\":\"\",\"likelihood\":3,\"impact\":3}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/risks").with(lead).contentType(json).content("{\"title\":\"x\",\"likelihood\":9,\"impact\":3}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/risks/" + id + "/status").with(lead).contentType(json).content("{\"status\":\"CLOSED\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/risks/" + id + "/status").with(lead).contentType(json).content("{\"status\":\"OPEN\"}")).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/risks/" + UUID.randomUUID()).with(lead)).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/risks/" + UUID.randomUUID()).with(lead).contentType(json).content("{\"title\":\"x\",\"likelihood\":1,\"impact\":1}")).andExpect(status().isNotFound());
    }
}
