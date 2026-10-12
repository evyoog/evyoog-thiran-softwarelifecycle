package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vyoog.clause.ClauseService;
import com.vyoog.clause.ClauseTemplateService;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.ScopeType;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0950 (F41, D27, D41): standard templates for the clause register: applying one adds what the register lacks, leaves what it has,
 * is audited and repeatable, and is the Administrators' to do. Against a real PostgreSQL, service calls and HTTP with real tokens.
 *
 * <p>The ISO 26262 template adds to the shared test database's register, so every assertion is about the template's own sections.
 */
@AutoConfigureMockMvc
class ClauseTemplateIT extends IntegrationTestBase {

    private static final String TEMPLATE = "iso-26262";

    @Autowired ClauseTemplateService templates;
    @Autowired ClauseService clauses;
    @Autowired MockMvc mvc;

    private UUID admin;
    private int size;

    @BeforeEach
    void fixtures() {
        admin = newAdministrator();
        size = templates.all().stream().filter(t -> t.id().equals(TEMPLATE)).findFirst().orElseThrow().clauses().size();
        // each test starts from a register without this standard: the template's sections are removed from earlier runs
        jdbc.update("DELETE FROM trace_link WHERE to_type = 'CLAUSE' AND to_id IN (SELECT id FROM clause WHERE lower(standard) = 'iso 26262')");
        jdbc.update("DELETE FROM clause WHERE lower(standard) = 'iso 26262'");
    }

    private int appliedEvents() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'clause.template_applied' AND after->>'template' = ?", Integer.class, TEMPLATE);
    }

    private int inRegister() {
        return jdbc.queryForObject("SELECT count(*) FROM clause WHERE lower(standard) = 'iso 26262'", Integer.class);
    }

    @Test
    void VYB0950_AC9_applyingATemplateAddsItsClausesAsOrdinaryClausesAndIsAudited() {
        int eventsBefore = appliedEvents();
        var result = templates.apply(TEMPLATE, admin);

        assertThat(result.added()).isEqualTo(size);
        assertThat(result.skipped()).isZero();
        assertThat(inRegister()).isEqualTo(size);
        var row = clauses.register().stream().filter(r -> "ISO 26262".equals(r.standard()) && "6-9".equals(r.section())).findFirst().orElseThrow();
        assertThat(row.text()).startsWith("Software unit verification. ");
        assertThat(row.coverage()).isEqualTo("UNMAPPED");
        assertThat(appliedEvents()).isEqualTo(eventsBefore + 1);
        assertThat(jdbc.queryForObject("SELECT (after->>'added')::int FROM audit_event WHERE action = 'clause.template_applied' ORDER BY occurred_at DESC, id DESC LIMIT 1", Integer.class)).isEqualTo(size);
        // an ordinary clause afterwards: it can be edited like any other
        clauses.update(row.id(), "ISO 26262", "6-9", "Our own wording.", admin);
        assertThat(clauses.mappingOf(row.id()).text()).isEqualTo("Our own wording.");
    }

    @Test
    void VYB0950_AC10_applyingAgainAddsNothingAndChangesNothing() {
        templates.apply(TEMPLATE, admin);
        var row = clauses.register().stream().filter(r -> "ISO 26262".equals(r.standard()) && "8-6".equals(r.section())).findFirst().orElseThrow();
        clauses.update(row.id(), "ISO 26262", "8-6", "Edited after applying.", admin);

        var again = templates.apply(TEMPLATE, admin);

        assertThat(again.added()).isZero();
        assertThat(again.skipped()).isEqualTo(size);
        assertThat(again.skippedSections()).hasSize(size).contains("8-6");
        assertThat(inRegister()).isEqualTo(size);
        assertThat(clauses.mappingOf(row.id()).text()).isEqualTo("Edited after applying.");   // the edit survived
    }

    @Test
    void VYB0950_AC11_aClauseAlreadyThereIsLeftExactlyAsItIsAndTheRestAreAdded() {
        clauses.create("iso 26262", "6-9", "Our own text for unit verification.", admin);        // another spelling of the standard and the section

        var result = templates.apply(TEMPLATE, admin);

        assertThat(result.added()).isEqualTo(size - 1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.skippedSections()).containsExactly("6-9");
        assertThat(jdbc.queryForObject("SELECT text FROM clause WHERE lower(standard) = 'iso 26262' AND section = '6-9'", String.class)).isEqualTo("Our own text for unit verification.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clause WHERE lower(standard) = 'iso 26262' AND section = '6-9'", Integer.class)).isEqualTo(1);
    }

    @Test
    void VYB0950_AC12_theListSaysHowManyOfEachTemplateTheRegisterAlreadyHas() {
        assertThat(templates.summaries().stream().filter(t -> t.id().equals(TEMPLATE)).findFirst().orElseThrow().alreadyInRegister()).isZero();
        clauses.create("ISO 26262", "2-5", "Present already.", admin);
        assertThat(templates.summaries().stream().filter(t -> t.id().equals(TEMPLATE)).findFirst().orElseThrow().alreadyInRegister()).isEqualTo(1);
        templates.apply(TEMPLATE, admin);
        var s = templates.summaries().stream().filter(t -> t.id().equals(TEMPLATE)).findFirst().orElseThrow();
        assertThat(s.alreadyInRegister()).isEqualTo(s.clauses()).isEqualTo(size);
    }

    @Test
    void VYB0950_AC13_anUnknownTemplateIsRefusedAndNothingIsAdded() {
        assertThatThrownBy(() -> templates.apply("no-such-template", admin)).isInstanceOf(NoSuchElementException.class);
        assertThat(inRegister()).isZero();
    }

    // ------------------------------------------------------------------ HTTP

    private JwtRequestPostProcessor aPersonWith(AccessRole role, boolean platform) {
        String id = unique("p");
        UUID user = users.upsert("sub-" + id, id + "@it.test", id).getId();
        Portfolio p = newPortfolio();
        if (role != null && platform) grants.grant(user, role, ScopeType.PLATFORM, null, null, user);
        else if (role != null) grantOnCapability(user, role, p.capabilityId());
        return jwt().jwt(j -> j.subject("sub-" + id).claim("email", id + "@it.test").claim("preferred_username", id).claim("azp", "vyoog-web"));
    }

    @Test
    void VYB0950_AC14_onlyAnAdministratorAppliesATemplateAndAnyoneSignedInReadsTheList() throws Exception {
        String url = "/api/v1/clauses/templates/" + TEMPLATE + "/apply";
        mvc.perform(post(url).with(aPersonWith(AccessRole.BUSINESS_ANALYST, false))).andExpect(status().isForbidden());
        mvc.perform(post(url).with(aPersonWith(AccessRole.COMPLIANCE_LEAD, false))).andExpect(status().isForbidden());
        mvc.perform(post(url).with(aPersonWith(null, false))).andExpect(status().isForbidden());
        mvc.perform(post(url)).andExpect(status().isUnauthorized());
        assertThat(inRegister()).isZero();

        mvc.perform(post(url).with(aPersonWith(AccessRole.ADMINISTRATOR, true))).andExpect(status().isOk())
            .andExpect(jsonPath("$.added").value(size)).andExpect(jsonPath("$.skipped").value(0)).andExpect(jsonPath("$.templateId").value(TEMPLATE));
        mvc.perform(post(url).with(aPersonWith(AccessRole.ADMINISTRATOR, true))).andExpect(status().isOk()).andExpect(jsonPath("$.added").value(0));
        mvc.perform(post("/api/v1/clauses/templates/nope/apply").with(aPersonWith(AccessRole.ADMINISTRATOR, true))).andExpect(status().isNotFound());

        JwtRequestPostProcessor viewer = aPersonWith(AccessRole.VIEWER, false);
        mvc.perform(get("/api/v1/clauses/templates").with(viewer)).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == '" + TEMPLATE + "')].clauses").value(size))
            .andExpect(jsonPath("$[?(@.id == '" + TEMPLATE + "')].alreadyInRegister").value(size));
        mvc.perform(get("/api/v1/clauses/templates")).andExpect(status().isUnauthorized());
    }
}
