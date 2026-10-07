package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vyoog.brief.Brief;
import com.vyoog.brief.BriefSection;
import com.vyoog.brief.BriefService;
import com.vyoog.brief.BriefTarget;
import com.vyoog.evidence.TestCase;
import com.vyoog.evidence.TestCaseService;
import com.vyoog.identity.AccessRole;
import com.vyoog.proposal.AiProposalService;
import com.vyoog.proposal.AiProposalService.Decision;
import com.vyoog.proposal.ProposalKind;
import com.vyoog.proposal.ProposalState;
import com.vyoog.requirements.Requirement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0938 (F30): the one review endpoint for AI proposals, against a real PostgreSQL: what recording does (nothing is
 * applied), what accepting does through the ordinary services, what refuses it, who may decide, and that a brief carries
 * only what a person accepted.
 */
@AutoConfigureMockMvc
class AiProposalIT extends IntegrationTestBase {

    @Autowired AiProposalService proposals;
    @Autowired TestCaseService testCases;
    @Autowired BriefService briefs;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;

    private Portfolio p;
    private UUID author;
    private UUID reviewer;
    private UUID admin;

    @BeforeEach
    void actors() {
        p = newPortfolio();
        author = newUser("author");
        reviewer = newUser("reviewer");
        admin = newAdministrator();
    }

    private ObjectNode rewrite(String statement) {
        ObjectNode n = json.createObjectNode().put("statement", statement);
        n.putArray("changes").add("made it testable");
        return n;
    }

    private ObjectNode testCase(String title) {
        return json.createObjectNode().put("category", "INDIVIDUAL").put("title", title)
            .put("description", "steps").put("rationale", "because");
    }

    private ObjectNode elaboration(String detail) {
        return json.createObjectNode().put("detail", detail);
    }

    private String statementOf(UUID requirementId) {
        return jdbc.queryForObject("SELECT statement FROM requirement WHERE id = ?", String.class, requirementId);
    }

    private int revisionOf(UUID requirementId) {
        return jdbc.queryForObject("SELECT revision FROM requirement WHERE id = ?", Integer.class, requirementId);
    }

    private int testCaseRowsFor(UUID requirementId) {
        return jdbc.queryForObject("SELECT count(*) FROM trace_link WHERE to_id = ? AND link_type = 'VERIFIES'", Integer.class, requirementId);
    }

    // ------------------------------------------------------------------ recording

    @Test
    void VYB0938_AC9_recordingAProposalAppliesNothingAndLeavesItPendingAndAudited() {
        Requirement r = newRequirement(p, author);
        String before = statementOf(r.getId());

        UUID id = proposals.record(ProposalKind.REWRITE, r.getId(), rewrite("A rewritten statement."), "test-model", reviewer);

        var got = proposals.get(id);
        assertThat(got.state()).isEqualTo(ProposalState.PENDING);
        assertThat(got.requirementKey()).isEqualTo(r.getKey());
        assertThat(got.requirementRevision()).isEqualTo(r.getRevision());
        assertThat(got.stale()).isFalse();
        assertThat(got.payload().path("statement").asText()).isEqualTo("A rewritten statement.");
        assertThat(statementOf(r.getId())).as("nothing was applied").isEqualTo(before);
        assertThat(revisionOf(r.getId())).isEqualTo(r.getRevision());
        assertThat(auditCount(id, "ai-proposal.proposed")).isEqualTo(1);
    }

    @Test
    void VYB0938_AC9_aProposalForARequirementThatDoesNotExistIsRefusedAndOneWithNoRequirementIsOnlyAllowedForARewrite() {
        assertThatThrownBy(() -> proposals.record(ProposalKind.REWRITE, UUID.randomUUID(), rewrite("x"), "m", reviewer))
            .isInstanceOf(java.util.NoSuchElementException.class);
        assertThatThrownBy(() -> proposals.record(ProposalKind.TEST_CASE, null, testCase("x"), "m", reviewer))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(proposals.get(proposals.record(ProposalKind.REWRITE, null, rewrite("drafted before it exists"), "m", reviewer)).requirementId()).isNull();
    }

    // ------------------------------------------------------------------ accepting

    @Test
    void VYB0938_AC10_acceptingARewriteEditsTheRequirementThroughTheOrdinaryServiceAndRecordsIt() {
        Requirement r = newRequirement(p, author);
        UUID id = proposals.record(ProposalKind.REWRITE, r.getId(), rewrite("The system shall respond within 2 seconds."), "test-model", reviewer);

        var done = proposals.decide(id, Decision.ACCEPT, null, null, reviewer);

        assertThat(done.state()).isEqualTo(ProposalState.ACCEPTED);
        assertThat(done.decidedByName()).isNotBlank();
        assertThat(done.appliedType()).isEqualTo("REQUIREMENT");
        assertThat(done.appliedId()).isEqualTo(r.getId());
        assertThat(done.acceptedPayload()).as("not edited").isNull();
        assertThat(statementOf(r.getId())).isEqualTo("The system shall respond within 2 seconds.");
        assertThat(revisionOf(r.getId())).as("a normal revision").isEqualTo(r.getRevision() + 1);
        assertThat(auditCount(r.getId(), "requirement.revised")).isEqualTo(1);
        assertThat(auditCount(id, "ai-proposal.accepted")).isEqualTo(1);
    }

    @Test
    void VYB0938_AC10_aPersonCanEditBeforeAcceptingAndTheOriginalIsKept() {
        Requirement r = newRequirement(p, author);
        UUID id = proposals.record(ProposalKind.REWRITE, r.getId(), rewrite("The AI wording."), "test-model", reviewer);

        var done = proposals.decide(id, Decision.ACCEPT, Map.of("statement", "The person's wording."), null, reviewer);

        assertThat(statementOf(r.getId())).isEqualTo("The person's wording.");
        assertThat(done.payload().path("statement").asText()).as("what the AI proposed is never overwritten").isEqualTo("The AI wording.");
        assertThat(done.acceptedPayload().path("statement").asText()).isEqualTo("The person's wording.");
        assertThat(auditCount(id, "ai-proposal.accepted")).isEqualTo(1);
    }

    @Test
    void VYB0938_AC10_aRewriteWithNoRequirementIsDecidedAndRecordedButNothingIsApplied() {
        UUID id = proposals.record(ProposalKind.REWRITE, null, rewrite("For a requirement being drafted."), "m", reviewer);

        var done = proposals.decide(id, Decision.ACCEPT, null, null, reviewer);

        assertThat(done.state()).isEqualTo(ProposalState.ACCEPTED);
        assertThat(done.appliedType()).isNull();
    }

    @Test
    void VYB0938_AC11_aRequirementThatChangedSinceIsNotOverwrittenAndTheProposalCanStillBeRejected() {
        Requirement r = newRequirement(p, author);
        UUID id = proposals.record(ProposalKind.REWRITE, r.getId(), rewrite("The AI wording."), "m", reviewer);
        requirementService.update(r.getId(), r.getRevision(), r.getTitle(), "Someone edited it meanwhile.", r.getType(), r.getPriority(), r.getCapabilityId(), author);

        assertThat(proposals.get(id).stale()).isTrue();
        assertThatThrownBy(() -> proposals.decide(id, Decision.ACCEPT, null, null, reviewer))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("has changed since this was proposed");

        assertThat(statementOf(r.getId())).isEqualTo("Someone edited it meanwhile.");
        assertThat(proposals.get(id).state()).isEqualTo(ProposalState.PENDING);
        assertThat(proposals.decide(id, Decision.REJECT, null, "out of date", reviewer).state()).isEqualTo(ProposalState.REJECTED);
    }

    @Test
    void VYB0938_AC12_anApprovedRequirementStillNeedsAChangeRequestSoAcceptingARewriteIsRefusedAndLeavesItPending() {
        Requirement r = approved(newRequirement(p, author), author, admin);
        UUID id = proposals.record(ProposalKind.REWRITE, r.getId(), rewrite("The AI wording."), "m", reviewer);
        String before = statementOf(r.getId());

        assertThatThrownBy(() -> proposals.decide(id, Decision.ACCEPT, null, null, reviewer))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("raise a change request");

        assertThat(statementOf(r.getId())).isEqualTo(before);
        assertThat(proposals.get(id).state()).as("the failed apply rolled back the decision").isEqualTo(ProposalState.PENDING);
    }

    @Test
    void VYB0938_AC13_acceptingATestCaseDraftsItLinkedToTheRequirementAndAnEditIsWhatIsDrafted() {
        Requirement r = newRequirement(p, author);
        UUID id = proposals.record(ProposalKind.TEST_CASE, r.getId(), testCase("AI title"), "test-model", reviewer);
        assertThat(testCaseRowsFor(r.getId())).as("no test case until it is accepted").isZero();

        var done = proposals.decide(id, Decision.ACCEPT, Map.of("title", "Person's title"), null, reviewer);

        assertThat(done.appliedType()).isEqualTo("TEST_CASE");
        assertThat(testCaseRowsFor(r.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title FROM test_case WHERE id = ?", String.class, done.appliedId())).isEqualTo("Person's title");
        assertThat(jdbc.queryForObject("SELECT status FROM test_case WHERE id = ?", String.class, done.appliedId())).isEqualTo("DRAFT");
        assertThat(auditCount(done.appliedId(), "test-case.drafted")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ deciding

    @Test
    void VYB0938_AC14_aProposalIsDecidedOnceAndARejectionKeepsItsReason() {
        Requirement r = newRequirement(p, author);
        UUID rejected = proposals.record(ProposalKind.TEST_CASE, r.getId(), testCase("Nope"), "m", reviewer);

        var done = proposals.decide(rejected, Decision.REJECT, null, "  duplicates VY-1  ", reviewer);

        assertThat(done.state()).isEqualTo(ProposalState.REJECTED);
        assertThat(done.decisionReason()).isEqualTo("duplicates VY-1");
        assertThat(testCaseRowsFor(r.getId())).isZero();
        assertThat(auditCount(rejected, "ai-proposal.rejected")).isEqualTo(1);
        assertThatThrownBy(() -> proposals.decide(rejected, Decision.ACCEPT, null, null, reviewer))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("already rejected");

        UUID accepted = proposals.record(ProposalKind.TEST_CASE, r.getId(), testCase("Yes"), "m", reviewer);
        proposals.decide(accepted, Decision.ACCEPT, null, null, reviewer);
        assertThatThrownBy(() -> proposals.decide(accepted, Decision.REJECT, null, null, reviewer))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("already accepted");
        assertThat(testCaseRowsFor(r.getId())).isEqualTo(1);
    }

    @Test
    void VYB0938_AC14_twoPeopleAcceptingTheSameProposalAtOnceDraftExactlyOneTestCase() throws Exception {
        Requirement r = newRequirement(p, author);
        UUID id = proposals.record(ProposalKind.TEST_CASE, r.getId(), testCase("Once only"), "m", reviewer);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Object>> both = List.of(() -> attempt(id), () -> attempt(id));
            int succeeded = 0;
            for (Future<Object> f : pool.invokeAll(both)) if (f.get() == Boolean.TRUE) succeeded++;

            assertThat(succeeded).isEqualTo(1);
            assertThat(testCaseRowsFor(r.getId())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private Object attempt(UUID id) {
        try {
            proposals.decide(id, Decision.ACCEPT, null, null, reviewer);
            return Boolean.TRUE;
        } catch (IllegalStateException e) {
            return Boolean.FALSE;
        }
    }

    @Test
    void VYB0938_AC15_onlyTheFieldsOfThatKindCanBeEditedAndNeverToNothing() {
        Requirement r = newRequirement(p, author);
        UUID id = proposals.record(ProposalKind.TEST_CASE, r.getId(), testCase("A title"), "m", reviewer);

        assertThatThrownBy(() -> proposals.decide(id, Decision.ACCEPT, Map.of("category", "DEPENDENCY"), null, reviewer))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("can change only");
        assertThatThrownBy(() -> proposals.decide(id, Decision.ACCEPT, Map.of("title", "   "), null, reviewer))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be empty");
        assertThatThrownBy(() -> proposals.decide(id, Decision.REJECT, Map.of("title", "x"), null, reviewer))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nothing to edit");
        assertThat(proposals.get(id).state()).isEqualTo(ProposalState.PENDING);
        assertThat(testCaseRowsFor(r.getId())).isZero();
    }

    // ------------------------------------------------------------------ briefs

    private record BriefFixture(Requirement requirement, UUID developer) {}

    private BriefFixture briefable() {
        Requirement r = approved(newRequirement(p, author), author, admin);
        testCases.draft("Covers " + r.getKey(), "steps", TestCase.Category.INDIVIDUAL, r.getId(), author);
        return new BriefFixture(r, newUser("dev"));
    }

    private String brief(BriefFixture f, boolean reviewed) {
        Brief b = briefs.generate(p.applicationId(), "App", List.of(p.capabilityId()), BriefTarget.HUMAN, f.developer(), author,
            BriefSection.ALL, reviewed);
        return b.getContent();
    }

    @Test
    void VYB0938_AC16_aBriefCarriesOnlyWhatAPersonAcceptedAndGeneratingNeverCallsTheAi() {
        BriefFixture f = briefable();
        UUID pending = proposals.record(ProposalKind.BRIEF_ELABORATION, f.requirement().getId(), elaboration("PENDING-TEXT"), "m", reviewer);

        assertThat(brief(f, true)).as("pending is not accepted").doesNotContain("PENDING-TEXT");

        proposals.decide(pending, Decision.ACCEPT, null, null, reviewer);
        assertThat(brief(f, true)).contains("PENDING-TEXT").contains("accepted by a person");
        assertThat(brief(f, false)).as("not asked for").doesNotContain("PENDING-TEXT");
    }

    @Test
    void VYB0938_AC16_aRejectedElaborationNeverReachesABriefAndAnEditedOneUsesTheEditedText() {
        BriefFixture f = briefable();
        UUID rejected = proposals.record(ProposalKind.BRIEF_ELABORATION, f.requirement().getId(), elaboration("REJECTED-TEXT"), "m", reviewer);
        proposals.decide(rejected, Decision.REJECT, null, "wrong", reviewer);
        assertThat(brief(f, true)).doesNotContain("REJECTED-TEXT");

        UUID edited = proposals.record(ProposalKind.BRIEF_ELABORATION, f.requirement().getId(), elaboration("AI-TEXT"), "m", reviewer);
        proposals.decide(edited, Decision.ACCEPT, Map.of("detail", "PERSON-TEXT"), null, reviewer);

        assertThat(brief(f, true)).contains("PERSON-TEXT").doesNotContain("AI-TEXT").doesNotContain("REJECTED-TEXT");
    }

    @Test
    void VYB0938_AC17_aNewerAcceptedElaborationReplacesTheOlderAndANewPendingOneSupersedesAnOlderPending() {
        BriefFixture f = briefable();
        UUID first = proposals.record(ProposalKind.BRIEF_ELABORATION, f.requirement().getId(), elaboration("FIRST"), "m", reviewer);
        proposals.decide(first, Decision.ACCEPT, null, null, reviewer);
        UUID second = proposals.record(ProposalKind.BRIEF_ELABORATION, f.requirement().getId(), elaboration("SECOND"), "m", reviewer);
        UUID third = proposals.record(ProposalKind.BRIEF_ELABORATION, f.requirement().getId(), elaboration("THIRD"), "m", reviewer);

        assertThat(proposals.get(second).state()).as("replaced while still pending").isEqualTo(ProposalState.SUPERSEDED);
        assertThat(brief(f, true)).as("the first stays until a newer one is accepted").contains("FIRST");

        proposals.decide(third, Decision.ACCEPT, null, null, reviewer);

        assertThat(proposals.get(first).state()).isEqualTo(ProposalState.SUPERSEDED);
        assertThat(brief(f, true)).contains("THIRD").doesNotContain("FIRST").doesNotContain("SECOND");
    }

    @Test
    void VYB0938_AC17_anElaborationWrittenForEarlierWordsIsNotOfferedAgain() {
        BriefFixture f = briefable();
        UUID id = proposals.record(ProposalKind.BRIEF_ELABORATION, f.requirement().getId(), elaboration("FOR-OLD-WORDS"), "m", reviewer);
        proposals.decide(id, Decision.ACCEPT, null, null, reviewer);
        assertThat(brief(f, true)).contains("FOR-OLD-WORDS");

        jdbc.update("UPDATE requirement SET revision = revision + 1 WHERE id = ?", f.requirement().getId()); // a later revision

        assertThat(brief(f, true)).doesNotContain("FOR-OLD-WORDS");
        assertThat(proposals.acceptedBriefElaborations(List.of(f.requirement().getId()))).isEmpty();
    }

    // ------------------------------------------------------------------ HTTP

    private JwtRequestPostProcessor aPersonWith(AccessRole role) {
        String id = unique("p");
        UUID user = users.upsert("sub-" + id, id + "@it.test", id).getId();
        if (role != null) grantOnCapability(user, role, p.capabilityId());
        return jwt().jwt(j -> j.subject("sub-" + id).claim("email", id + "@it.test").claim("preferred_username", id).claim("azp", "vyoog-web"));
    }

    private static final String ACCEPT = "{\"decision\":\"ACCEPT\"}";

    @Test
    void VYB0938_AC18_whoMayDecideIsWhoCouldMakeTheSameChangeByHand() throws Exception {
        Requirement r = newRequirement(p, author);
        JwtRequestPostProcessor analyst = aPersonWith(AccessRole.BUSINESS_ANALYST);
        JwtRequestPostProcessor tester = aPersonWith(AccessRole.TESTER);
        JwtRequestPostProcessor viewer = aPersonWith(AccessRole.VIEWER);
        JwtRequestPostProcessor nobody = aPersonWith(null);
        UUID rewriteId = proposals.record(ProposalKind.REWRITE, r.getId(), rewrite("By the analyst."), "m", reviewer);
        UUID caseId = proposals.record(ProposalKind.TEST_CASE, r.getId(), testCase("By the tester"), "m", reviewer);
        UUID elabId = proposals.record(ProposalKind.BRIEF_ELABORATION, r.getId(), elaboration("By the analyst too."), "m", reviewer);

        for (JwtRequestPostProcessor who : List.of(tester, viewer, nobody)) {
            decide(rewriteId, ACCEPT, who).andExpect(status().isForbidden());
            decide(elabId, ACCEPT, who).andExpect(status().isForbidden());
        }
        for (JwtRequestPostProcessor who : List.of(analyst, viewer, nobody)) {
            decide(caseId, ACCEPT, who).andExpect(status().isForbidden());
        }
        decide(rewriteId, ACCEPT, null).andExpect(status().isUnauthorized());
        decide(rewriteId, ACCEPT, jwt().jwt(j -> j.subject("svc").claim("azp", "ci-bot"))).andExpect(status().is4xxClientError());
        assertThat(proposals.get(rewriteId).state()).as("none of those decided it").isEqualTo(ProposalState.PENDING);

        decide(caseId, "{\"decision\":\"ACCEPT\",\"edits\":{\"title\":\"Edited by the tester\"}}", tester)
            .andExpect(status().isOk()).andExpect(jsonPath("$.appliedType").value("TEST_CASE"))
            .andExpect(jsonPath("$.acceptedPayload.title").value("Edited by the tester"));
        decide(elabId, "{\"decision\":\"REJECT\",\"reason\":\"not useful\"}", analyst)
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("REJECTED")).andExpect(jsonPath("$.decisionReason").value("not useful"));
        decide(elabId, ACCEPT, analyst).andExpect(status().isConflict());
        // last, because accepting a rewrite revises the requirement and the others were made against the revision before
        decide(rewriteId, ACCEPT, analyst).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ACCEPTED"))
            .andExpect(jsonPath("$.appliedType").value("REQUIREMENT"));
    }

    @Test
    void VYB0938_AC18_aProposalWithNoRequirementNeedsTheRuleAnywhereAndBadInputIsRefusedInWords() throws Exception {
        UUID free = proposals.record(ProposalKind.REWRITE, null, rewrite("for a draft"), "m", reviewer);
        JwtRequestPostProcessor viewer = aPersonWith(AccessRole.VIEWER);
        decide(free, ACCEPT, viewer).andExpect(status().isForbidden());
        decide(free, ACCEPT, aPersonWith(AccessRole.BUSINESS_ANALYST)).andExpect(status().isOk());

        UUID id = proposals.record(ProposalKind.REWRITE, null, rewrite("again"), "m", reviewer);
        JwtRequestPostProcessor analyst = aPersonWith(AccessRole.BUSINESS_ANALYST);
        decide(id, "{\"decision\":\"MAYBE\"}", analyst).andExpect(status().isBadRequest());
        decide(id, "{\"decision\":\"ACCEPT\",\"edits\":{\"colour\":\"red\"}}", analyst).andExpect(status().isBadRequest());
        decide(UUID.randomUUID(), ACCEPT, analyst).andExpect(status().isNotFound());
    }

    @Test
    void VYB0938_AC19_theListDefaultsToPendingFiltersByStateAndKindAndAnyoneSignedInMayRead() throws Exception {
        Requirement r = newRequirement(p, author);
        UUID pendingCase = proposals.record(ProposalKind.TEST_CASE, r.getId(), testCase("Pending one"), "m", reviewer);
        UUID decidedCase = proposals.record(ProposalKind.TEST_CASE, r.getId(), testCase("Decided one"), "m", reviewer);
        proposals.decide(decidedCase, Decision.REJECT, null, "no", reviewer);
        proposals.record(ProposalKind.REWRITE, r.getId(), rewrite("Another"), "m", reviewer);
        JwtRequestPostProcessor viewer = aPersonWith(AccessRole.VIEWER);
        String forThis = "&requirementId=" + r.getId();

        mvc.perform(get("/api/v1/ai-proposals?size=50" + forThis).with(viewer)).andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(2));
        mvc.perform(get("/api/v1/ai-proposals?state=REJECTED" + forThis).with(viewer)).andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.content[0].id").value(decidedCase.toString()))
            .andExpect(jsonPath("$.content[0].decidedByName").exists());
        mvc.perform(get("/api/v1/ai-proposals?state=ALL" + forThis).with(viewer)).andExpect(jsonPath("$.content.length()").value(3));
        mvc.perform(get("/api/v1/ai-proposals?kind=TEST_CASE" + forThis).with(viewer)).andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(pendingCase.toString()));
        mvc.perform(get("/api/v1/ai-proposals?state=NONSENSE").with(viewer)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ai-proposals?kind=NONSENSE").with(viewer)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ai-proposals/" + pendingCase).with(viewer)).andExpect(status().isOk())
            .andExpect(jsonPath("$.payload.title").value("Pending one")).andExpect(jsonPath("$.requirementKey").value(r.getKey()));
        mvc.perform(get("/api/v1/ai-proposals/" + UUID.randomUUID()).with(viewer)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/ai-proposals")).andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions decide(UUID id, String body, JwtRequestPostProcessor who) throws Exception {
        var req = post("/api/v1/ai-proposals/" + id + "/decision").contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(who == null ? req : req.with(who));
    }
}
