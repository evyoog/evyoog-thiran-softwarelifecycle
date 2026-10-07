package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vyoog.defect.Defect;
import com.vyoog.defect.DefectLifecycleService;
import com.vyoog.defect.DefectService;
import com.vyoog.defect.DefectSeverity;
import com.vyoog.defect.DefectState;
import com.vyoog.defect.FoundIn;
import com.vyoog.defect.RootCause;
import com.vyoog.identity.AccessRole;
import com.vyoog.release.Release;
import com.vyoog.release.ReleaseService;
import com.vyoog.requirements.Requirement;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0931 (F15): the defect lifecycle: fix, close, reopen, edit, assign, comment, links to a test, a run and a release,
 * and the state filter. Against a real PostgreSQL, service calls and HTTP with real tokens.
 */
@AutoConfigureMockMvc
class DefectLifecycleIT extends IntegrationTestBase {

    @Autowired DefectService defects;
    @Autowired DefectLifecycleService lifecycle;
    @Autowired ReleaseService releases;
    @Autowired MockMvc mvc;

    private Portfolio p;
    private UUID author, dev, tester;

    @BeforeEach
    void fixtures() {
        p = newPortfolio();
        author = newUser("author");
        dev = newUser("dev");
        tester = newUser("tester");
    }

    private Defect raise(String title) {
        Requirement r = newRequirement(p, author);
        return defects.raise(title, DefectSeverity.MEDIUM, r.getId(), FoundIn.QA, tester);
    }

    private List<String> transitions(UUID defectId) {
        return jdbc.queryForList("SELECT from_state || '>' || to_state FROM defect_transition WHERE defect_id = ? ORDER BY changed_at, id",
            String.class, defectId);
    }

    /**
     * How many times this person was told, by kind. The notification row itself is coalesced inside a digest window (repeats
     * collapse into one), so the outbox event written on every call is what counts the calls.
     */
    private int told(UUID user, String kind) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type = ? AND payload->>'userId' = ?", Integer.class,
            "notification." + kind, user.toString());
    }

    // ------------------------------------------------------------------ moves

    @Test
    void VYB0931_AC1_aDeveloperMarksAnOpenDefectFixedAndTheMoveIsRecordedAndAuditedAndTheTesterIsToldToVerify() {
        Defect d = raise("Login 500");
        lifecycle.assign(d.getId(), dev, tester, tester);

        Defect fixed = lifecycle.markFixed(d.getId(), "  patched in build 12 ", dev);

        assertThat(fixed.getState()).isEqualTo(DefectState.FIXED);
        assertThat(transitions(d.getId())).containsExactly("OPEN>FIXED");
        assertThat(jdbc.queryForObject("SELECT reason FROM defect_transition WHERE defect_id = ?", String.class, d.getId())).isEqualTo("patched in build 12");
        assertThat(auditCount(d.getId(), "defect.fixed")).isEqualTo(1);
        assertThat(told(tester, "defect-fixed")).isEqualTo(1);
        assertThatThrownBy(() -> lifecycle.markFixed(d.getId(), null, dev)).isInstanceOf(IllegalStateException.class).hasMessageContaining("fixed");
        assertThatThrownBy(() -> lifecycle.markFixed(UUID.randomUUID(), null, dev)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void VYB0931_AC2_aFixedDefectIsClosedOnlyWithARootCauseAndACLosedOneCannotBeClosedAgain() {
        Defect d = raise("Export is blank");
        lifecycle.markFixed(d.getId(), null, dev);

        assertThatThrownBy(() -> defects.close(d.getId(), tester)).isInstanceOf(IllegalStateException.class).hasMessageContaining("root cause");
        defects.classify(d.getId(), RootCause.CODING_ERROR, tester);
        Defect closed = defects.close(d.getId(), tester);

        assertThat(closed.getState()).isEqualTo(DefectState.CLOSED);
        assertThat(transitions(d.getId())).containsExactly("OPEN>FIXED", "FIXED>CLOSED");
        assertThatThrownBy(() -> defects.close(d.getId(), tester)).isInstanceOf(IllegalStateException.class).hasMessageContaining("already closed");
        assertThat(auditCount(d.getId(), "defect.closed")).isEqualTo(1);
    }

    @Test
    void VYB0931_AC2_aDefectCanStillBeClosedStraightFromOpen() {
        Defect d = raise("Not a defect");
        defects.classify(d.getId(), RootCause.UNKNOWN, tester);
        defects.close(d.getId(), tester);
        assertThat(transitions(d.getId())).containsExactly("OPEN>CLOSED");
    }

    @Test
    void VYB0931_AC3_aFixedOrClosedDefectIsReopenedWithAReasonThatIsRecordedAndTheDeveloperIsToldTheRootCauseStays() {
        Defect d = raise("Regression");
        lifecycle.assign(d.getId(), dev, null, tester);
        lifecycle.markFixed(d.getId(), null, dev);
        defects.classify(d.getId(), RootCause.REQUIREMENT_AMBIGUITY, tester);

        assertThatThrownBy(() -> lifecycle.reopen(d.getId(), "  ", tester)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");
        Defect reopened = lifecycle.reopen(d.getId(), "still happens on Safari", tester); // FIXED to OPEN
        lifecycle.markFixed(d.getId(), null, dev);
        defects.close(d.getId(), tester);
        lifecycle.reopen(d.getId(), "came back in production", tester);                    // CLOSED to OPEN

        assertThat(reopened.getState()).isEqualTo(DefectState.OPEN);
        assertThat(transitions(d.getId())).containsExactly("OPEN>FIXED", "FIXED>OPEN", "OPEN>FIXED", "FIXED>CLOSED", "CLOSED>OPEN");
        assertThat(jdbc.queryForList("SELECT reason FROM defect_transition WHERE defect_id = ? AND to_state = 'OPEN' ORDER BY changed_at", String.class, d.getId()))
            .containsExactly("still happens on Safari", "came back in production");
        assertThat(jdbc.queryForObject("SELECT root_cause FROM defect WHERE id = ?", String.class, d.getId())).isEqualTo("REQUIREMENT_AMBIGUITY");
        assertThat(told(dev, "defect-reopened")).isEqualTo(2);
        assertThat(auditCount(d.getId(), "defect.reopened")).isEqualTo(2);
        assertThatThrownBy(() -> lifecycle.reopen(d.getId(), "again", tester)).isInstanceOf(IllegalStateException.class).hasMessageContaining("already open");
    }

    @Test
    void VYB0931_AC3_theDatabaseRefusesAReopenWithNoReasonAndAMoveToTheSameState() {
        Defect d = raise("Rows");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO defect_transition (defect_id, from_state, to_state) VALUES (?, 'FIXED', 'OPEN')", d.getId()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO defect_transition (defect_id, from_state, to_state) VALUES (?, 'OPEN', 'OPEN')", d.getId()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------ edit / assign

    @Test
    void VYB0931_AC4_titleSeverityAndWhereItWasFoundCanBeEditedAndStateAndRootCauseAreUntouchedAndAClosedDefectIsRefused() {
        Defect d = raise("Typo in titel");
        defects.classify(d.getId(), RootCause.DATA, tester);

        Defect edited = lifecycle.edit(d.getId(), "  Typo in title ", DefectSeverity.CRITICAL, FoundIn.PRODUCTION, tester);

        assertThat(edited.getTitle()).isEqualTo("Typo in title");
        assertThat(edited.getSeverity()).isEqualTo(DefectSeverity.CRITICAL);
        assertThat(edited.getFoundIn()).isEqualTo(FoundIn.PRODUCTION);
        assertThat(edited.getState()).isEqualTo(DefectState.OPEN);
        assertThat(edited.getRootCause()).isEqualTo(RootCause.DATA);
        assertThat(jdbc.queryForObject("SELECT \"before\"->>'title' || '>' || (\"after\"->>'title') FROM audit_event WHERE object_id = ? AND action = 'defect.edited'",
            String.class, d.getId())).isEqualTo("Typo in titel>Typo in title");
        assertThatThrownBy(() -> lifecycle.edit(d.getId(), " ", DefectSeverity.LOW, FoundIn.QA, tester)).isInstanceOf(IllegalArgumentException.class);

        defects.close(d.getId(), tester);
        assertThatThrownBy(() -> lifecycle.edit(d.getId(), "x", DefectSeverity.LOW, FoundIn.QA, tester))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("reopen");
    }

    @Test
    void VYB0931_AC5_assignmentSetsDeveloperAndTesterNotifiesOnlyTheNewlyAssignedAndCanClearBoth() {
        Defect d = raise("Who");
        UUID other = newUser("other");

        lifecycle.assign(d.getId(), dev, tester, author);
        assertThat(told(dev, "defect-routed-developer")).isEqualTo(1);
        assertThat(told(tester, "defect-routed-tester")).isEqualTo(1);

        Defect again = lifecycle.assign(d.getId(), dev, other, author); // developer unchanged, tester changed
        assertThat(again.getDeveloperId()).isEqualTo(dev);
        assertThat(again.getTesterId()).isEqualTo(other);
        assertThat(told(dev, "defect-routed-developer")).isEqualTo(1);       // not told again
        assertThat(told(other, "defect-routed-tester")).isEqualTo(1);

        Defect cleared = lifecycle.assign(d.getId(), null, null, author);
        assertThat(cleared.getDeveloperId()).isNull();
        assertThat(cleared.getTesterId()).isNull();
        assertThat(auditCount(d.getId(), "defect.assigned")).isEqualTo(3);
        assertThatThrownBy(() -> lifecycle.assign(d.getId(), UUID.randomUUID(), null, author)).isInstanceOf(NoSuchElementException.class);
        assertThat(lifecycle.assign(d.getId(), null, null, author).getDeveloperId()).isNull(); // the refused call changed nothing
    }

    // ------------------------------------------------------------------ links

    @Test
    void VYB0931_AC6_aDefectLinksToOneTestOneRunAndOneReleaseWhichAreReplacedAsAWholeAndClearedByNull() {
        Defect d = raise("Linked");
        Release rel = releases.create(unique("R"));
        UUID tc = jdbc.queryForObject("INSERT INTO test_case (key, title) VALUES (?, 'The test') RETURNING id", UUID.class, unique("TC"));
        UUID run = jdbc.queryForObject("INSERT INTO test_run (build_label, source) VALUES (?, 'it') RETURNING id", UUID.class, unique("b"));

        lifecycle.link(d.getId(), tc, run, rel.getId(), tester);
        var detail = lifecycle.detail(d.getId());
        assertThat(detail.testCase().id()).isEqualTo(tc);
        assertThat(detail.testCase().label()).contains("The test");
        assertThat(detail.testRun().id()).isEqualTo(run);
        assertThat(detail.release().label()).isEqualTo(rel.getName());

        lifecycle.link(d.getId(), null, null, rel.getId(), tester);
        detail = lifecycle.detail(d.getId());
        assertThat(detail.testCase()).isNull();
        assertThat(detail.testRun()).isNull();
        assertThat(detail.release().id()).isEqualTo(rel.getId());
        assertThat(auditCount(d.getId(), "defect.linked")).isEqualTo(2);

        assertThatThrownBy(() -> lifecycle.link(d.getId(), UUID.randomUUID(), null, null, tester)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> lifecycle.link(d.getId(), null, UUID.randomUUID(), null, tester)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> lifecycle.link(d.getId(), null, null, UUID.randomUUID(), tester)).isInstanceOf(NoSuchElementException.class);
        assertThat(lifecycle.detail(d.getId()).release().id()).isEqualTo(rel.getId()); // a refused call changed nothing
    }

    @Test
    void VYB0931_AC6_deletingALinkedTestCaseClearsTheLinkAndLeavesTheDefect() {
        Defect d = raise("Orphan");
        UUID tc = jdbc.queryForObject("INSERT INTO test_case (key, title) VALUES (?, 'Gone') RETURNING id", UUID.class, unique("TC"));
        lifecycle.link(d.getId(), tc, null, null, tester);
        jdbc.update("DELETE FROM test_case WHERE id = ?", tc);
        assertThat(lifecycle.detail(d.getId()).testCase()).isNull();
        assertThat(lifecycle.detail(d.getId()).defect().getKey()).isEqualTo(d.getKey());
    }

    @Test
    void VYB0931_AC6_theDetailShowsTheRequirementTheNamesAndTheMovesInOrder() {
        Defect d = raise("Detail");
        lifecycle.assign(d.getId(), dev, tester, tester);
        lifecycle.markFixed(d.getId(), "done", dev);
        var detail = lifecycle.detail(d.getId());
        assertThat(detail.requirementKey()).isNotBlank();
        assertThat(detail.developerName()).isNotBlank();
        assertThat(detail.testerName()).isNotBlank();
        assertThat(detail.transitions()).singleElement().satisfies(t -> {
            assertThat(t.from()).isEqualTo(DefectState.OPEN);
            assertThat(t.to()).isEqualTo(DefectState.FIXED);
            assertThat(t.changedBy()).isEqualTo(dev);
            assertThat(t.changedByName()).isNotBlank();
        });
        assertThatThrownBy(() -> lifecycle.detail(UUID.randomUUID())).isInstanceOf(NoSuchElementException.class);
    }

    // --------------------------------------------------------------- comments

    @Test
    void VYB0931_AC7_commentsAreAppendedInOrderWithTheirAuthorAndNeverEditedOrDeleted() {
        Defect d = raise("Talk");
        lifecycle.comment(d.getId(), "  first  ", dev);
        lifecycle.comment(d.getId(), "second", tester);

        var comments = lifecycle.comments(d.getId());
        assertThat(comments).extracting(DefectLifecycleService.Comment::body).containsExactly("first", "second");
        assertThat(comments).extracting(DefectLifecycleService.Comment::authorId).containsExactly(dev, tester);
        assertThat(comments.get(0).authorName()).isNotBlank();
        assertThat(auditCount(d.getId(), "defect.commented")).isEqualTo(2);

        assertThatThrownBy(() -> lifecycle.comment(d.getId(), "   ", dev)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lifecycle.comment(d.getId(), "x".repeat(4001), dev)).isInstanceOf(IllegalArgumentException.class);
        assertThat(lifecycle.comment(d.getId(), "x".repeat(4000), dev).body()).hasSize(4000);
        assertThatThrownBy(() -> jdbc.update("UPDATE defect_comment SET body = 'edited' WHERE defect_id = ?", d.getId()))
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM defect_comment WHERE defect_id = ?", d.getId())).hasMessageContaining("append-only");
        assertThatThrownBy(() -> lifecycle.comment(UUID.randomUUID(), "hi", dev)).isInstanceOf(NoSuchElementException.class);
    }

    // ----------------------------------------------------------------- the list

    private Defect tagged(String tag, String suffix, DefectSeverity severity) {
        Requirement r = newRequirement(p, author);
        return defects.raise(tag + " " + suffix, severity, r.getId(), FoundIn.QA, tester);
    }

    @Test
    void VYB0931_AC8_theListFiltersByStateWithOpenTheDefaultAndAllShowingEverythingMostSevereFirst() {
        String tag = unique("Tag");
        Defect low = tagged(tag, "low", DefectSeverity.LOW);
        Defect crit = tagged(tag, "crit", DefectSeverity.CRITICAL);
        Defect fixed = tagged(tag, "fixed", DefectSeverity.HIGH);
        Defect closed = tagged(tag, "closed", DefectSeverity.MEDIUM);
        lifecycle.markFixed(fixed.getId(), null, dev);
        defects.classify(closed.getId(), RootCause.DATA, tester);
        defects.close(closed.getId(), tester);

        DefectLifecycleService.Filter open = new DefectLifecycleService.Filter(null, null, null, null, tag);
        assertThat(lifecycle.list(open, PageRequest.of(0, 20)).getContent()).extracting(r -> r.defect().getKey())
            .containsExactly(crit.getKey(), low.getKey()); // OPEN by default, CRITICAL before LOW
        assertThat(keys(tag, "FIXED")).containsExactly(fixed.getKey());
        assertThat(keys(tag, "closed")).containsExactly(closed.getKey()); // case-insensitive
        assertThat(keys(tag, "ALL")).containsExactly(crit.getKey(), fixed.getKey(), closed.getKey(), low.getKey());
        assertThat(lifecycle.list(new DefectLifecycleService.Filter("ALL", null, null, null, tag), PageRequest.of(1, 3)).getContent()).hasSize(1);
        assertThat(lifecycle.list(new DefectLifecycleService.Filter("ALL", null, null, null, tag), PageRequest.of(0, 3)).getTotalElements()).isEqualTo(4);
    }

    private List<String> keys(String tag, String state) {
        return lifecycle.list(new DefectLifecycleService.Filter(state, null, null, null, tag), PageRequest.of(0, 20)).getContent().stream()
            .map(r -> r.defect().getKey()).toList();
    }

    @Test
    void VYB0931_AC8_theListAlsoNarrowsBySeverityReleaseAssigneeAndASearchWhoseWildcardsAreLiteral() {
        String tag = unique("Tag");
        Release rel = releases.create(unique("R"));
        Defect a = tagged(tag, "alpha 100% done", DefectSeverity.HIGH);
        Defect b = tagged(tag, "beta", DefectSeverity.LOW);
        lifecycle.link(a.getId(), null, null, rel.getId(), tester);
        lifecycle.assign(b.getId(), dev, null, tester);

        assertThat(lifecycle.list(new DefectLifecycleService.Filter("ALL", DefectSeverity.HIGH, null, null, tag), PageRequest.of(0, 10)).getContent())
            .extracting(r -> r.defect().getKey()).containsExactly(a.getKey());
        var byRelease = lifecycle.list(new DefectLifecycleService.Filter("ALL", null, rel.getId(), null, null), PageRequest.of(0, 10)).getContent();
        assertThat(byRelease).extracting(r -> r.defect().getKey()).containsExactly(a.getKey());
        assertThat(byRelease.get(0).releaseName()).isEqualTo(rel.getName());
        var byDev = lifecycle.list(new DefectLifecycleService.Filter("ALL", null, null, dev, tag), PageRequest.of(0, 10)).getContent();
        assertThat(byDev).extracting(r -> r.defect().getKey()).containsExactly(b.getKey());
        assertThat(byDev.get(0).developerName()).isNotBlank();
        assertThat(lifecycle.list(new DefectLifecycleService.Filter("ALL", null, null, null, "100%"), PageRequest.of(0, 10)).getContent())
            .extracting(r -> r.defect().getKey()).contains(a.getKey());
        assertThat(lifecycle.list(new DefectLifecycleService.Filter("ALL", null, null, null, "%_" + unique("zz")), PageRequest.of(0, 10)).getContent()).isEmpty();
        assertThat(lifecycle.list(new DefectLifecycleService.Filter("ALL", null, null, null, a.getKey().toLowerCase()), PageRequest.of(0, 10)).getContent())
            .extracting(r -> r.defect().getKey()).containsExactly(a.getKey());
    }

    // ------------------------------------------------------------------- HTTP

    private JwtRequestPostProcessor token(UUID user, String email, String name) {
        return jwt().jwt(j -> j.subject("sub-" + name).claim("email", email).claim("preferred_username", name).claim("azp", "vyoog-web"));
    }

    private JwtRequestPostProcessor aPersonWith(AccessRole role) {
        String id = unique("p");
        UUID user = users.upsert("sub-" + id, id + "@it.test", id).getId();
        if (role != null) grantOnCapability(user, role, p.capabilityId());
        return token(user, id + "@it.test", id);
    }

    @Test
    void VYB0931_AC9_whoMayDoWhatOverHttpTheAssignedDeveloperFixesATesterDecidesAnyoneCanComment() throws Exception {
        Defect d = raise("Http");
        String devId = unique("dev");
        UUID assigned = users.upsert("sub-" + devId, devId + "@it.test", devId).getId(); // no role at all, just assigned
        lifecycle.assign(d.getId(), assigned, null, tester);
        JwtRequestPostProcessor assignedDev = token(assigned, devId + "@it.test", devId);
        JwtRequestPostProcessor otherDev = aPersonWith(AccessRole.DEVELOPER);
        JwtRequestPostProcessor analyst = aPersonWith(AccessRole.BUSINESS_ANALYST);
        JwtRequestPostProcessor viewer = aPersonWith(AccessRole.VIEWER);
        JwtRequestPostProcessor tst = aPersonWith(AccessRole.TESTER);
        String base = "/api/v1/defects/" + d.getId();
        String json = MediaType.APPLICATION_JSON_VALUE;

        // fix: not someone it is not assigned to, unless a Tester
        mvc.perform(post(base + "/fix").with(otherDev)).andExpect(status().isForbidden());
        mvc.perform(post(base + "/fix").with(analyst)).andExpect(status().isForbidden());
        mvc.perform(post(base + "/fix")).andExpect(status().isUnauthorized());
        mvc.perform(post(base + "/fix").with(assignedDev).contentType(json).content("{\"note\":\"patched\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("FIXED"));
        mvc.perform(post(base + "/fix").with(tst)).andExpect(status().isConflict()); // a Tester may, but it is no longer open

        // reopen, edit, assign, links: Tester only, not even the assigned developer
        for (JwtRequestPostProcessor who : List.of(assignedDev, otherDev, analyst, viewer)) {
            mvc.perform(post(base + "/reopen").with(who).contentType(json).content("{\"reason\":\"no\"}")).andExpect(status().isForbidden());
            mvc.perform(put(base).with(who).contentType(json).content("{\"title\":\"t\",\"severity\":\"LOW\",\"foundIn\":\"QA\"}")).andExpect(status().isForbidden());
            mvc.perform(put(base + "/assignment").with(who).contentType(json).content("{}")).andExpect(status().isForbidden());
            mvc.perform(put(base + "/links").with(who).contentType(json).content("{}")).andExpect(status().isForbidden());
        }
        mvc.perform(post(base + "/reopen").with(tst).contentType(json).content("{\"reason\":\"still broken\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("OPEN"));
        mvc.perform(post(base + "/reopen").with(tst).contentType(json).content("{\"reason\":\" \"}")).andExpect(status().isBadRequest());
        mvc.perform(put(base).with(tst).contentType(json).content("{\"title\":\"Better title\",\"severity\":\"HIGH\",\"foundIn\":\"UAT\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Better title")).andExpect(jsonPath("$.severity").value("HIGH"));
        mvc.perform(put(base).with(tst).contentType(json).content("{\"title\":\"t\",\"severity\":\"NOPE\",\"foundIn\":\"QA\"}")).andExpect(status().isBadRequest());
        mvc.perform(put(base + "/assignment").with(tst).contentType(json).content("{\"developerId\":\"" + assigned + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.developerId").value(assigned.toString()));
        mvc.perform(put(base + "/links").with(tst).contentType(json).content("{\"releaseId\":\"" + UUID.randomUUID() + "\"}")).andExpect(status().isNotFound());

        // comment: any signed-in person, not a service account
        for (JwtRequestPostProcessor who : List.of(viewer, assignedDev, analyst)) {
            mvc.perform(post(base + "/comments").with(who).contentType(json).content("{\"body\":\"a thought\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.body").value("a thought")).andExpect(jsonPath("$.authorName").exists());
        }
        mvc.perform(post(base + "/comments").with(viewer).contentType(json).content("{\"body\":\"  \"}")).andExpect(status().isBadRequest());
        mvc.perform(post(base + "/comments").with(jwt().jwt(j -> j.subject("svc").claim("azp", "ci-bot"))).contentType(json).content("{\"body\":\"x\"}"))
            .andExpect(status().is4xxClientError());
        mvc.perform(post(base + "/comments").contentType(json).content("{\"body\":\"x\"}")).andExpect(status().isUnauthorized());

        // reads are open to any signed-in person
        mvc.perform(get(base).with(viewer)).andExpect(status().isOk())
            .andExpect(jsonPath("$.defect.key").value(d.getKey())).andExpect(jsonPath("$.transitions.length()").value(2));
        mvc.perform(get(base + "/comments").with(viewer)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/api/v1/defects/" + UUID.randomUUID()).with(viewer)).andExpect(status().isNotFound());
    }

    @Test
    void VYB0931_AC9_theListEndpointTakesTheStateFilterAndRefusesAnUnknownOne() throws Exception {
        String tag = unique("Tag");
        Defect open = tagged(tag, "open", DefectSeverity.HIGH);
        Defect fixed = tagged(tag, "fixed", DefectSeverity.HIGH);
        lifecycle.markFixed(fixed.getId(), null, dev);
        JwtRequestPostProcessor viewer = aPersonWith(AccessRole.VIEWER);

        mvc.perform(get("/api/v1/defects").param("q", tag).with(viewer)).andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.content[0].key").value(open.getKey()))
            .andExpect(jsonPath("$.content[0].requirementKey").exists());
        mvc.perform(get("/api/v1/defects").param("q", tag).param("state", "FIXED").with(viewer)).andExpect(jsonPath("$.content[0].key").value(fixed.getKey()));
        mvc.perform(get("/api/v1/defects").param("q", tag).param("state", "ALL").with(viewer)).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/defects").param("state", "BOGUS").with(viewer)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/defects").param("severity", "BOGUS").with(viewer)).andExpect(status().is4xxClientError());
    }
}
