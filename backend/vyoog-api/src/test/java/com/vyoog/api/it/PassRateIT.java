package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vyoog.evidence.RequirementPassRateService;
import com.vyoog.evidence.RequirementPassRateService.PassRate;
import com.vyoog.evidence.TestCase;
import com.vyoog.evidence.TestCaseService;
import com.vyoog.evidence.TestExecutionService;
import com.vyoog.evidence.TestManagementService;
import com.vyoog.evidence.VerificationResult;
import com.vyoog.evidence.VerificationService;
import com.vyoog.requirements.Requirement;
import com.vyoog.trace.TraceGraphService;
import com.vyoog.trace.TraceLinkType;
import com.vyoog.trace.TraceObjectType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0927 (F14): the pass rate per requirement. Each test case that verifies a requirement is judged by its latest
 * result at the requirement's current revision (CI and manual together); not run and stale are not failures.
 */
@AutoConfigureMockMvc
class PassRateIT extends IntegrationTestBase {

    @Autowired RequirementPassRateService rates;
    @Autowired TestCaseService testCases;
    @Autowired VerificationService verification;
    @Autowired TestManagementService mgmt;
    @Autowired TestExecutionService exec;
    @Autowired TraceGraphService trace;
    @Autowired MockMvc mvc;

    private PassRate rateOf(Requirement r) {
        List<PassRate> rows = rates.list(r.getKey(), PageRequest.of(0, 10)).getContent();
        assertThat(rows).as("rows for " + r.getKey()).hasSize(1);
        return rows.get(0);
    }

    private void ci(TestCase tc, Requirement r, VerificationResult result) {
        var run = verification.findOrCreateRun(unique("build"), "ci-" + unique("src"));
        verification.ingest(run, new VerificationService.ResultInput(tc.getKey(), tc.getTitle(), result, List.of(r.getKey())));
    }

    @Test
    void VYB0927_AC1_eachCaseCountsOnceByItsLatestResultAndOneWithNoResultIsNotRunNotFailed() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("qa");
        Requirement r = newRequirement(p, actor);
        TestCase passing = testCases.draft(unique("P"), null, null, r.getId(), actor);
        TestCase failing = testCases.draft(unique("F"), null, null, r.getId(), actor);
        testCases.draft(unique("N"), null, null, r.getId(), actor);
        ci(passing, r, VerificationResult.PASS);
        ci(failing, r, VerificationResult.FAIL);

        PassRate rate = rateOf(r);

        assertThat(rate.cases()).isEqualTo(3);
        assertThat(rate.passed()).isEqualTo(1);
        assertThat(rate.failed()).isEqualTo(1);
        assertThat(rate.notRun()).isEqualTo(1);
        assertThat(rate.stale()).isZero();
        assertThat(rate.passRate()).isEqualTo(0.5); // of the two that ran: the one never run does not count against it
        assertThat(rate.revision()).isEqualTo(r.getRevision());
        assertThat(rate.lastResultAt()).isNotNull();
    }

    @Test
    void VYB0927_AC2_aLaterPassReplacesAnEarlierFailSoAFixedBugStopsCountingAgainstTheRequirement() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("qa");
        Requirement r = newRequirement(p, actor);
        TestCase tc = testCases.draft(unique("T"), null, null, r.getId(), actor);
        ci(tc, r, VerificationResult.FAIL);
        assertThat(rateOf(r).failed()).isEqualTo(1);

        ci(tc, r, VerificationResult.PASS);

        PassRate rate = rateOf(r);
        assertThat(rate.passed()).isEqualTo(1);
        assertThat(rate.failed()).isZero();
        assertThat(rate.passRate()).isEqualTo(1.0);
        // and the other way round: a later fail replaces an earlier pass
        ci(tc, r, VerificationResult.FAIL);
        assertThat(rateOf(r).failed()).isEqualTo(1);
        assertThat(rateOf(r).passed()).isZero();
    }

    @Test
    void VYB0927_AC3_aResultFromAnEarlierRevisionIsStaleNotPassedAndTheRateIsNullNotZero() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("qa");
        Requirement r = newRequirement(p, actor);
        TestCase tc = testCases.draft(unique("T"), null, null, r.getId(), actor);
        ci(tc, r, VerificationResult.PASS);
        assertThat(rateOf(r).passed()).isEqualTo(1);

        Requirement edited = requirementService.update(r.getId(), r.getRevision(), r.getTitle(), r.getStatement() + " Revised.",
            r.getType(), r.getPriority(), r.getCapabilityId(), actor);

        PassRate rate = rateOf(edited);
        assertThat(rate.revision()).isEqualTo(edited.getRevision());
        assertThat(rate.passed()).isZero();
        assertThat(rate.stale()).isEqualTo(1);
        assertThat(rate.notRun()).isZero();
        assertThat(rate.passRate()).isNull();
    }

    @Test
    void VYB0927_AC4_manualAndCiResultsCountTogetherAndABlockedCaseIsNotRun() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("qa");
        Requirement r = newRequirement(p, actor);
        TestCase manualPass = testCases.draft(unique("M"), null, null, r.getId(), actor);
        TestCase manualBlocked = testCases.draft(unique("B"), null, null, r.getId(), actor);
        TestCase viaCi = testCases.draft(unique("C"), null, null, r.getId(), actor);
        var plan = mgmt.createPlan(unique("Plan"), null, p.applicationId(), null, actor);
        var suite = mgmt.createSuite(plan.id(), unique("Suite"), null, actor);
        mgmt.setSuiteCases(suite.id(), List.of(manualPass.getId(), manualBlocked.getId()), actor);
        var run = mgmt.createRun(suite.id(), null, null, actor);
        exec.start(run.run().id(), actor);
        var started = mgmt.getRun(run.run().id());
        exec.recordCaseResult(run.run().id(), started.cases().get(0).id(), "PASS", null, actor);
        exec.recordCaseResult(run.run().id(), started.cases().get(1).id(), "BLOCKED", "env down", actor);
        exec.complete(run.run().id(), actor);
        ci(viaCi, r, VerificationResult.FAIL);

        PassRate rate = rateOf(r);

        assertThat(rate.cases()).isEqualTo(3);
        assertThat(rate.passed()).isEqualTo(1);   // the manual pass
        assertThat(rate.failed()).isEqualTo(1);   // the CI fail
        assertThat(rate.notRun()).isEqualTo(1);   // the blocked case wrote nothing
    }

    @Test
    void VYB0927_AC5_onlyRequirementsWithATestCaseAreListedWorstFirstAndSearchableAndPaged() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("qa");
        String tag = unique("Tag");
        Requirement none = requirementService.create(tag + " none", "The system shall " + unique("a") + ".", "FUNCTIONAL", "MEDIUM",
            com.vyoog.requirements.Placement.capability(p.capabilityId()), actor);
        Requirement good = requirementService.create(tag + " good", "The system shall " + unique("b") + ".", "FUNCTIONAL", "MEDIUM",
            com.vyoog.requirements.Placement.capability(p.capabilityId()), actor);
        Requirement bad = requirementService.create(tag + " bad", "The system shall " + unique("c") + ".", "FUNCTIONAL", "MEDIUM",
            com.vyoog.requirements.Placement.capability(p.capabilityId()), actor);
        ci(testCases.draft(unique("G"), null, null, good.getId(), actor), good, VerificationResult.PASS);
        ci(testCases.draft(unique("X"), null, null, bad.getId(), actor), bad, VerificationResult.FAIL);

        var page = rates.list(tag, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(PassRate::key).containsExactly(bad.getKey(), good.getKey()); // worst first; `none` has no case
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(rates.list(tag, PageRequest.of(0, 1)).getContent()).extracting(PassRate::key).containsExactly(bad.getKey());
        assertThat(rates.list(tag, PageRequest.of(1, 1)).getContent()).extracting(PassRate::key).containsExactly(good.getKey());
        assertThat(rates.list(none.getKey(), PageRequest.of(0, 10)).getContent()).isEmpty();
        assertThat(rates.list("100%_" + unique("zz"), PageRequest.of(0, 10)).getContent()).isEmpty(); // wildcards in the search are literal
    }

    @Test
    void VYB0927_AC6_anySignedInPersonCanReadItAndNoTokenIsRefused() throws Exception {
        Portfolio p = newPortfolio();
        UUID actor = newUser("qa");
        Requirement r = newRequirement(p, actor);
        ci(testCases.draft(unique("T"), null, null, r.getId(), actor), r, VerificationResult.PASS);
        String id = unique("viewer");
        users.upsert("sub-" + id, id + "@it.test", id);

        mvc.perform(get("/api/v1/quality/pass-rates").param("q", r.getKey()))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/quality/pass-rates").param("q", r.getKey())
                .with(jwt().jwt(j -> j.subject("sub-" + id).claim("email", id + "@it.test").claim("preferred_username", id).claim("azp", "vyoog-web"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].key").value(r.getKey()))
            .andExpect(jsonPath("$.content[0].passed").value(1))
            .andExpect(jsonPath("$.content[0].passRate").value(1.0))
            .andExpect(jsonPath("$.content[0].cases").value(1));
    }

    @Test
    void VYB0927_AC7_readingItWritesNothing() {
        Portfolio p = newPortfolio();
        UUID actor = newUser("qa");
        Requirement r = newRequirement(p, actor);
        ci(testCases.draft(unique("T"), null, null, r.getId(), actor), r, VerificationResult.PASS);
        int before = jdbc.queryForObject("SELECT count(*) FROM verification WHERE requirement_id = ?", Integer.class, r.getId());
        String status = jdbc.queryForObject("SELECT status FROM requirement WHERE id = ?", String.class, r.getId());

        rates.list(r.getKey(), PageRequest.of(0, 10));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM verification WHERE requirement_id = ?", Integer.class, r.getId())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT status FROM requirement WHERE id = ?", String.class, r.getId())).isEqualTo(status);
    }
}
