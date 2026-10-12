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

import com.vyoog.clause.Clause;
import com.vyoog.clause.ClauseService;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.ScopeType;
import com.vyoog.requirements.Requirement;
import com.vyoog.trace.TraceGraphService;
import com.vyoog.trace.TraceLinkType;
import com.vyoog.trace.TraceObjectType;
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
 * VYB-0947 (F41, D38): the clause register and its control mapping: create, edit and delete a clause, map requirements to it, and the
 * coverage derived from them (unmapped, mapped, evidenced). Against a real PostgreSQL, service calls and HTTP with real tokens.
 */
@AutoConfigureMockMvc
class ClauseRegisterIT extends IntegrationTestBase {

    @Autowired ClauseService service;
    @Autowired TraceGraphService trace;
    @Autowired MockMvc mvc;

    private Portfolio p;
    private UUID author, admin;

    @BeforeEach
    void fixtures() {
        p = newPortfolio();
        author = newUser("author");
        admin = newAdministrator();
    }

    private String std() { return unique("STD"); }

    private Clause clause(String standard, String section) {
        return service.create(standard, section, "The organisation shall control access to " + unique("assets") + ".", admin);
    }

    private ClauseService.RegisterRow row(UUID clauseId) {
        return service.register().stream().filter(r -> r.id().equals(clauseId)).findFirst().orElseThrow();
    }

    private void passAtCurrentRevision(Requirement r) {
        int rev = jdbc.queryForObject("SELECT revision FROM requirement WHERE id = ?", Integer.class, r.getId());
        jdbc.update("INSERT INTO verification (requirement_id, requirement_revision, result) VALUES (?, ?, 'PASS')", r.getId(), rev);
    }

    private UUID testFor(Requirement r) {
        UUID t = jdbc.queryForObject("INSERT INTO test_case (key, title) VALUES (?, ?) RETURNING id", UUID.class, unique("TC"), unique("Test"));
        trace.createLink(TraceObjectType.TEST, t, TraceObjectType.REQUIREMENT, r.getId(), TraceLinkType.VERIFIES, author);
        return t;
    }

    // ------------------------------------------------------------------ the register

    @Test
    void VYB0947_AC1_aClauseIsTrimmedStoredAndAudited() {
        String s = std();
        Clause c = service.create("  " + s + "  ", "  A.5.1 ", "  Access is controlled.  ", admin);
        assertThat(c.getStandard()).isEqualTo(s);
        assertThat(c.getSection()).isEqualTo("A.5.1");
        assertThat(c.getText()).isEqualTo("Access is controlled.");
        assertThat(auditCount(c.getId(), "clause.created")).isEqualTo(1);
        assertThat(service.create(s, "  ", "No section.", admin).getSection()).isNull();
    }

    @Test
    void VYB0947_AC1_aClauseNeedsAStandardAndTextAndHasLimits() {
        assertThatThrownBy(() -> service.create(" ", "1", "text", admin)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("standard");
        assertThatThrownBy(() -> service.create(std(), "1", "  ", admin)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("text");
        assertThatThrownBy(() -> service.create("x".repeat(101), "1", "t", admin)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(std(), "x".repeat(101), "t", admin)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(std(), "1", "x".repeat(4001), admin)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void VYB0947_AC2_theSameSectionOfTheSameStandardIsNotRegisteredTwice() {
        String s = std();
        clause(s, "A.5.1");
        assertThatThrownBy(() -> clause(s.toLowerCase(), "a.5.1")).isInstanceOf(IllegalStateException.class).hasMessageContaining("already in the register");
        clause(std(), "A.5.1");            // another standard: fine
        clause(s, null);                   // no section: not a section clash
        clause(s, null);
    }

    @Test
    void VYB0947_AC3_aClauseCanBeEditedAndTheOldValuesAreOnTheAuditLog() {
        String s = std();
        Clause c = clause(s, "1.1");
        Clause other = clause(s, "1.2");

        service.update(c.getId(), s, "1.1", "Changed text.", admin);
        assertThat(service.mappingOf(c.getId()).text()).isEqualTo("Changed text.");
        assertThat(jdbc.queryForObject("SELECT before->>'text' FROM audit_event WHERE object_id = ? AND action = 'clause.updated'", String.class, c.getId()))
            .startsWith("The organisation shall control access");
        assertThatThrownBy(() -> service.update(c.getId(), s, "1.2", "x", admin)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.update(UUID.randomUUID(), s, "9", "x", admin)).isInstanceOf(NoSuchElementException.class);
        assertThat(other.getSection()).isEqualTo("1.2");
    }

    // ------------------------------------------------------------------ coverage

    @Test
    void VYB0947_AC4_aClauseIsUnmappedUntilARequirementIsMappedToIt() {
        Clause c = clause(std(), "1");
        assertThat(row(c.getId()).coverage()).isEqualTo("UNMAPPED");
        assertThat(row(c.getId()).mappedRequirements()).isZero();
    }

    @Test
    void VYB0947_AC5_mappedButNotEvidencedThenEvidencedWhenEveryCountingRequirementIsApprovedWithAPassingTest() {
        Clause c = clause(std(), "1");
        Requirement a = approved(newRequirement(p, author), author, admin);
        Requirement b = approved(newRequirement(p, author), author, admin);

        service.map(c.getId(), a.getId(), author);
        assertThat(row(c.getId()).coverage()).isEqualTo("MAPPED");

        passAtCurrentRevision(a);
        assertThat(row(c.getId()).coverage()).isEqualTo("EVIDENCED");

        service.map(c.getId(), b.getId(), author);                // b has no passing test yet
        assertThat(row(c.getId()).coverage()).isEqualTo("MAPPED");
        assertThat(row(c.getId()).mappedRequirements()).isEqualTo(2);
        assertThat(row(c.getId()).evidencedRequirements()).isEqualTo(1);

        passAtCurrentRevision(b);
        assertThat(row(c.getId()).coverage()).isEqualTo("EVIDENCED");
    }

    @Test
    void VYB0947_AC6_aPassingTestOnARequirementThatIsNotApprovedIsNotEvidence() {
        Clause c = clause(std(), "1");
        Requirement draft = newRequirement(p, author);
        service.map(c.getId(), draft.getId(), author);
        passAtCurrentRevision(draft);
        assertThat(row(c.getId()).coverage()).isEqualTo("MAPPED");
        assertThat(service.mappingOf(c.getId()).requirements()).singleElement().satisfies(r -> {
            assertThat(r.counts()).isTrue();
            assertThat(r.evidenced()).isFalse();
        });
    }

    @Test
    void VYB0947_AC7_evidenceFromAnEarlierRevisionIsStaleAndDoesNotCount() {
        Clause c = clause(std(), "1");
        Requirement r = approved(newRequirement(p, author), author, admin);
        service.map(c.getId(), r.getId(), author);
        int rev = jdbc.queryForObject("SELECT revision FROM requirement WHERE id = ?", Integer.class, r.getId());
        jdbc.update("INSERT INTO verification (requirement_id, requirement_revision, result) VALUES (?, ?, 'PASS')", r.getId(), rev - 1);

        assertThat(row(c.getId()).coverage()).isEqualTo("MAPPED");
        assertThat(service.mappingOf(c.getId()).requirements()).singleElement().satisfies(m -> {
            assertThat(m.evidenced()).isFalse();
            assertThat(m.staleEvidence()).isTrue();
        });
    }

    @Test
    void VYB0947_AC8_aRequirementThatIsRejectedDeletedOrReplacedStopsCountingButStaysListed() {
        Clause c = clause(std(), "1");
        Requirement rejected = newRequirement(p, author), gone = newRequirement(p, author), old = newRequirement(p, author), newer = newRequirement(p, author);
        for (Requirement r : new Requirement[] {rejected, gone, old}) service.map(c.getId(), r.getId(), author);
        assertThat(row(c.getId()).mappedRequirements()).isEqualTo(3);

        jdbc.update("UPDATE requirement SET status = 'REJECTED' WHERE id = ?", rejected.getId());
        jdbc.update("UPDATE requirement SET deleted_at = now() WHERE id = ?", gone.getId());
        jdbc.update("UPDATE requirement SET superseded_by_id = ? WHERE id = ?", newer.getId(), old.getId());

        assertThat(row(c.getId()).mappedRequirements()).isZero();
        assertThat(row(c.getId()).coverage()).isEqualTo("UNMAPPED");
        var listed = service.mappingOf(c.getId()).requirements();
        assertThat(listed).extracting(r -> r.key()).containsExactlyInAnyOrder(rejected.getKey(), old.getKey());   // a deleted one is not listed
        assertThat(listed).allSatisfy(r -> assertThat(r.counts()).isFalse());
        assertThat(listed).filteredOn(r -> r.key().equals(old.getKey())).singleElement().satisfies(r -> assertThat(r.replacedByKey()).isEqualTo(newer.getKey()));
    }

    // ------------------------------------------------------------------ the mapping

    @Test
    void VYB0947_AC9_theMappingShowsTheRequirementsTestsAndTheirLatestResultAtTheCurrentRevision() {
        Clause c = clause(std(), "1");
        Requirement r = approved(newRequirement(p, author), author, admin);
        service.map(c.getId(), r.getId(), author);
        UUID passing = testFor(r), failing = testFor(r), notRun = testFor(r);
        int rev = jdbc.queryForObject("SELECT revision FROM requirement WHERE id = ?", Integer.class, r.getId());
        jdbc.update("INSERT INTO verification (requirement_id, requirement_revision, test_case_id, result, verified_at) VALUES (?, ?, ?, 'FAIL', now() - interval '1 hour')", r.getId(), rev, passing);
        jdbc.update("INSERT INTO verification (requirement_id, requirement_revision, test_case_id, result) VALUES (?, ?, ?, 'PASS')", r.getId(), rev, passing);   // latest wins
        jdbc.update("INSERT INTO verification (requirement_id, requirement_revision, test_case_id, result) VALUES (?, ?, ?, 'FAIL')", r.getId(), rev, failing);
        jdbc.update("INSERT INTO verification (requirement_id, requirement_revision, test_case_id, result) VALUES (?, ?, ?, 'PASS')", r.getId(), rev - 1, notRun);  // an older revision

        var mapped = service.mappingOf(c.getId()).requirements().get(0);

        assertThat(mapped.key()).isEqualTo(r.getKey());
        assertThat(mapped.tests()).hasSize(3);
        assertThat(mapped.tests().stream().filter(t -> t.id().equals(passing)).findFirst().orElseThrow().result()).isEqualTo("PASS");
        assertThat(mapped.tests().stream().filter(t -> t.id().equals(failing)).findFirst().orElseThrow().result()).isEqualTo("FAIL");
        assertThat(mapped.tests().stream().filter(t -> t.id().equals(notRun)).findFirst().orElseThrow().result()).isNull();
    }

    @Test
    void VYB0947_AC10_mappingIsIdempotentAuditedAndUnmappingRemovesIt() {
        Clause c = clause(std(), "1");
        Requirement r = newRequirement(p, author);

        service.map(c.getId(), r.getId(), author);
        service.map(c.getId(), r.getId(), author);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trace_link WHERE to_type = 'CLAUSE' AND to_id = ?", Integer.class, c.getId())).isEqualTo(1);
        assertThat(auditCount(c.getId(), "clause.requirement_mapped")).isEqualTo(1);

        service.unmap(c.getId(), r.getId(), author);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trace_link WHERE to_type = 'CLAUSE' AND to_id = ?", Integer.class, c.getId())).isZero();
        assertThat(auditCount(c.getId(), "clause.requirement_unmapped")).isEqualTo(1);
        assertThatThrownBy(() -> service.unmap(c.getId(), r.getId(), author)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void VYB0947_AC11_aRejectedMissingOrReplacedRequirementCannotBeMappedAndNothingIsLinked() {
        Clause c = clause(std(), "1");
        Requirement rejected = newRequirement(p, author), old = newRequirement(p, author), newer = newRequirement(p, author);
        jdbc.update("UPDATE requirement SET status = 'REJECTED' WHERE id = ?", rejected.getId());
        jdbc.update("UPDATE requirement SET superseded_by_id = ? WHERE id = ?", newer.getId(), old.getId());

        assertThatThrownBy(() -> service.map(c.getId(), rejected.getId(), author)).isInstanceOf(IllegalStateException.class).hasMessageContaining("rejected");
        assertThatThrownBy(() -> service.map(c.getId(), old.getId(), author)).isInstanceOf(IllegalStateException.class).hasMessageContaining("replaced");
        assertThatThrownBy(() -> service.map(c.getId(), UUID.randomUUID(), author)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.map(UUID.randomUUID(), newer.getId(), author)).isInstanceOf(NoSuchElementException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trace_link WHERE to_type = 'CLAUSE' AND to_id = ?", Integer.class, c.getId())).isZero();
    }

    // ------------------------------------------------------------------ deleting

    @Test
    void VYB0947_AC12_aClauseThatIsStillLinkedIsNotDeletedAndAnUnlinkedOneIsAndItsOpenFindingsAreResolved() {
        Clause c = clause(std(), "1");
        Requirement r = newRequirement(p, author);
        service.map(c.getId(), r.getId(), author);
        assertThatThrownBy(() -> service.delete(c.getId(), admin)).isInstanceOf(IllegalStateException.class).hasMessageContaining("still linked");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clause WHERE id = ?", Integer.class, c.getId())).isEqualTo(1);

        service.unmap(c.getId(), r.getId(), author);
        UUID finding = jdbc.queryForObject("""
            INSERT INTO finding (rule_key, fingerprint, object_type, object_id, severity, title)
            VALUES ('compl', ?, 'CLAUSE', ?, 'crit', 'Unmapped control clause') RETURNING id""", UUID.class, unique("fp"), c.getId());
        service.delete(c.getId(), admin);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM clause WHERE id = ?", Integer.class, c.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM finding WHERE id = ?", String.class, finding)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT before->>'standard' FROM audit_event WHERE object_id = ? AND action = 'clause.deleted'", String.class, c.getId()))
            .isEqualTo(c.getStandard());
        assertThatThrownBy(() -> service.delete(c.getId(), admin)).isInstanceOf(NoSuchElementException.class);
    }

    // ------------------------------------------------------------------ HTTP

    private JwtRequestPostProcessor token(String id) {
        return jwt().jwt(j -> j.subject("sub-" + id).claim("email", id + "@it.test").claim("preferred_username", id).claim("azp", "vyoog-web"));
    }

    private JwtRequestPostProcessor aPersonWith(AccessRole role, boolean platform) {
        String id = unique("p");
        UUID user = users.upsert("sub-" + id, id + "@it.test", id).getId();
        if (role != null && platform) grants.grant(user, role, ScopeType.PLATFORM, null, null, user);
        else if (role != null) grantOnCapability(user, role, p.capabilityId());
        return token(id);
    }

    @Test
    void VYB0947_AC13_whoMayDoWhatOverHttpAdministratorsEditTheRegisterAuthorsMapAnyoneReadsNoTokenIsRefused() throws Exception {
        Clause c = clause(std(), "9");
        Requirement r = newRequirement(p, author);
        JwtRequestPostProcessor administrator = aPersonWith(AccessRole.ADMINISTRATOR, true);
        JwtRequestPostProcessor analyst = aPersonWith(AccessRole.BUSINESS_ANALYST, false);
        JwtRequestPostProcessor viewer = aPersonWith(AccessRole.VIEWER, false);
        String json = MediaType.APPLICATION_JSON_VALUE;
        String body = "{\"standard\":\"" + c.getStandard() + "\",\"section\":\"9\",\"text\":\"Edited over HTTP.\"}";
        String map = "/api/v1/clauses/" + c.getId() + "/requirements/" + r.getId();

        // editing the register: administrators only
        mvc.perform(put("/api/v1/clauses/" + c.getId()).with(analyst).contentType(json).content(body)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/clauses/" + c.getId()).with(analyst)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/clauses").with(analyst).contentType(json).content(body)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/clauses/" + c.getId()).contentType(json).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/clauses/" + c.getId()).with(administrator).contentType(json).content(body)).andExpect(status().isOk())
            .andExpect(jsonPath("$.text").value("Edited over HTTP."));

        // mapping: whoever may create trace links
        mvc.perform(put(map).with(viewer)).andExpect(status().isForbidden());
        mvc.perform(delete(map).with(viewer)).andExpect(status().isForbidden());
        mvc.perform(put(map)).andExpect(status().isUnauthorized());
        mvc.perform(put(map).with(analyst)).andExpect(status().isOk());
        mvc.perform(delete(map).with(analyst)).andExpect(status().isOk());
        mvc.perform(delete(map).with(analyst)).andExpect(status().isNotFound());

        // reading: any signed-in person
        mvc.perform(get("/api/v1/clauses/register").with(viewer)).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == '" + c.getId() + "')].coverage").value("UNMAPPED"));
        mvc.perform(get("/api/v1/clauses/" + c.getId() + "/mapping").with(viewer)).andExpect(status().isOk()).andExpect(jsonPath("$.requirements").isEmpty());
        mvc.perform(get("/api/v1/clauses/register")).andExpect(status().isUnauthorized());
    }

    @Test
    void VYB0947_AC14_badRequestsAreRefusedWithTheirReason() throws Exception {
        Clause c = clause(std(), "5");
        JwtRequestPostProcessor administrator = aPersonWith(AccessRole.ADMINISTRATOR, true);
        String json = MediaType.APPLICATION_JSON_VALUE;
        mvc.perform(post("/api/v1/clauses").with(administrator).contentType(json).content("{\"standard\":\"\",\"text\":\"t\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/clauses").with(administrator).contentType(json).content("{\"standard\":\"" + c.getStandard() + "\",\"section\":\"5\",\"text\":\"t\"}"))
            .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/clauses/" + UUID.randomUUID()).with(administrator).contentType(json).content("{\"standard\":\"s\",\"text\":\"t\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/clauses/" + UUID.randomUUID() + "/mapping").with(administrator)).andExpect(status().isNotFound());
    }
}
