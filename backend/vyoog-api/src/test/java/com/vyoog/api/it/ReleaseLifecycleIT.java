package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vyoog.identity.AccessRole;
import com.vyoog.release.Release;
import com.vyoog.release.ReleaseGate;
import com.vyoog.release.ReleaseGateConfigService;
import com.vyoog.release.ReleaseGateConfigService.Transition;
import com.vyoog.release.ReleaseGateException;
import com.vyoog.release.ReleaseLifecycleService;
import com.vyoog.release.ReleaseService;
import com.vyoog.release.ReleaseState;
import com.vyoog.requirements.Requirement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0928 (F13): the release state machine (PLANNED, OPEN, FROZEN, RELEASED, and FROZEN back to OPEN) and its
 * configurable readiness gates, against a real PostgreSQL. The gate configuration is platform-wide, so every test
 * that changes it puts it back.
 */
@AutoConfigureMockMvc
class ReleaseLifecycleIT extends IntegrationTestBase {

    @Autowired ReleaseService releases;
    @Autowired ReleaseLifecycleService lifecycle;
    @Autowired ReleaseGateConfigService gateConfig;
    @Autowired MockMvc mvc;

    private Portfolio p;
    private UUID author, admin;
    private List<ReleaseGateConfigService.Setting> originalGates;

    @BeforeEach
    void fixtures() {
        p = newPortfolio();
        author = newUser("author");
        admin = newAdministrator();
        originalGates = gateConfig.list();
    }

    @AfterEach
    void restoreGates() {
        for (var s : originalGates) gateConfig.update(s.transition(), s.gate(), s.enabled(), s.threshold(), admin);
    }

    private Release newRelease() {
        return releases.create(unique("R"));
    }

    private Requirement approvedRequirement() {
        return approved(newRequirement(p, author), author, admin);
    }

    /**
     * Creating and approving a requirement makes the detectors raise gaps on it (for instance "no verification" before
     * any test exists). A gate counts open critical gaps, so a fixture that wants a clean requirement resolves them, as
     * a person would by fixing the cause; the gap gate itself is tested with a gap that is left open.
     */
    private void resolveFindings(UUID requirementId) {
        jdbc.update("UPDATE finding SET state = 'RESOLVED' WHERE object_id = ?", requirementId);
    }

    /** An OPEN release holding one Approved requirement that has a passing test and an owner: ready for every default gate. */
    private Release readyRelease() {
        Release rel = newRelease();
        lifecycle.transition(rel.getId(), ReleaseState.OPEN, null, false, admin);
        Requirement r = approvedRequirement();
        jdbc.update("UPDATE requirement SET owner_id = ? WHERE id = ?", author, r.getId());
        UUID tc = jdbc.queryForObject("INSERT INTO test_case (key, title) VALUES (?, 'T') RETURNING id", UUID.class, unique("TC"));
        UUID run = jdbc.queryForObject("INSERT INTO test_run (build_label, source) VALUES (?, 'it') RETURNING id", UUID.class, unique("b"));
        jdbc.update("INSERT INTO verification (requirement_id, requirement_revision, test_case_id, test_run_id, result) "
            + "SELECT id, revision, ?, ?, 'PASS' FROM requirement WHERE id = ?", tc, run, r.getId());
        resolveFindings(r.getId());
        releases.commit(rel.getId(), r.getId(), admin, "in scope");
        return rel;
    }

    private ReleaseState stateOf(Release r) {
        return ReleaseState.valueOf(jdbc.queryForObject("SELECT state FROM release WHERE id = ?", String.class, r.getId()));
    }

    // ------------------------------------------------------------ the machine

    @Test
    void VYB0928_AC1_aReleaseGoesPlannedOpenFrozenReleasedAndEachMoveIsRecordedAndAudited() {
        Release rel = readyRelease();
        // readyRelease already opened it
        assertThat(stateOf(rel)).isEqualTo(ReleaseState.OPEN);

        lifecycle.transition(rel.getId(), ReleaseState.FROZEN, "scope agreed", false, admin);
        assertThat(stateOf(rel)).isEqualTo(ReleaseState.FROZEN);
        lifecycle.transition(rel.getId(), ReleaseState.RELEASED, null, false, admin);
        assertThat(stateOf(rel)).isEqualTo(ReleaseState.RELEASED);

        var history = lifecycle.history(rel.getId());
        assertThat(history).extracting(t -> t.from() + ">" + t.to()).containsExactly("PLANNED>OPEN", "OPEN>FROZEN", "FROZEN>RELEASED");
        assertThat(history.get(1).reason()).isEqualTo("scope agreed");
        assertThat(history).allSatisfy(t -> {
            assertThat(t.overridden()).isFalse();
            assertThat(t.changedBy()).isEqualTo(admin);
        });
        assertThat(auditCount(rel.getId(), "release.transitioned")).isEqualTo(3);
    }

    @Test
    void VYB0928_AC2_onlyTheDefinedMovesAreAllowedAndReleasedIsFinal() {
        Release rel = newRelease();
        for (ReleaseState bad : List.of(ReleaseState.PLANNED, ReleaseState.FROZEN, ReleaseState.RELEASED)) {
            assertThatThrownBy(() -> lifecycle.transition(rel.getId(), bad, "x", true, admin))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot go from PLANNED to " + bad);
        }
        lifecycle.transition(rel.getId(), ReleaseState.OPEN, null, false, admin);
        assertThatThrownBy(() -> lifecycle.transition(rel.getId(), ReleaseState.PLANNED, "x", true, admin)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lifecycle.transition(rel.getId(), ReleaseState.RELEASED, "x", true, admin)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lifecycle.transition(rel.getId(), ReleaseState.OPEN, "x", true, admin)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lifecycle.transition(rel.getId(), null, "x", true, admin)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lifecycle.transition(UUID.randomUUID(), ReleaseState.OPEN, null, false, admin))
            .isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(stateOf(rel)).isEqualTo(ReleaseState.OPEN);
        assertThat(lifecycle.history(rel.getId())).hasSize(1); // refused moves left no trace in the history
    }

    @Test
    void VYB0928_AC2_aReleasedReleaseCannotMoveAgain() {
        Release rel = readyRelease();
        lifecycle.transition(rel.getId(), ReleaseState.FROZEN, null, false, admin);
        lifecycle.transition(rel.getId(), ReleaseState.RELEASED, null, false, admin);
        for (ReleaseState to : ReleaseState.values()) {
            assertThatThrownBy(() -> lifecycle.transition(rel.getId(), to, "again", true, admin)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(lifecycle.options(rel.getId())).isEmpty();
    }

    @Test
    void VYB0928_AC3_aFrozenReleaseCanBeReopenedOnlyWithAReasonAndThenItsScopeCanChangeAgain() {
        Release rel = readyRelease();
        lifecycle.transition(rel.getId(), ReleaseState.FROZEN, null, false, admin);
        assertThatThrownBy(() -> lifecycle.transition(rel.getId(), ReleaseState.OPEN, "  ", false, admin))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");
        assertThat(stateOf(rel)).isEqualTo(ReleaseState.FROZEN);

        lifecycle.transition(rel.getId(), ReleaseState.OPEN, "a late requirement", false, admin);

        assertThat(stateOf(rel)).isEqualTo(ReleaseState.OPEN);
        assertThat(lifecycle.history(rel.getId()).get(2).reason()).isEqualTo("a late requirement");
        Requirement late = approvedRequirement();
        releases.commit(rel.getId(), late.getId(), admin, "late");
        assertThat(releases.scope(rel.getId())).contains(late.getId());
    }

    // ------------------------------------------------------------- scope lock

    @Test
    void VYB0928_AC4_theScopeOfAFrozenOrReleasedReleaseIsLockedAndOfAPlannedOrOpenOneIsNot() {
        Release rel = newRelease();
        Requirement a = approvedRequirement(), b = approvedRequirement();
        releases.commit(rel.getId(), a.getId(), admin, "planned: fine");
        lifecycle.transition(rel.getId(), ReleaseState.OPEN, null, false, admin);
        releases.commit(rel.getId(), b.getId(), admin, "open: fine");

        lifecycle.transition(rel.getId(), ReleaseState.FROZEN, "go", true, admin);
        Requirement c = approvedRequirement();
        assertThatThrownBy(() -> releases.commit(rel.getId(), c.getId(), admin, "late"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("frozen").hasMessageContaining("reopen");
        assertThatThrownBy(() -> releases.removeFromScope(rel.getId(), a.getId(), admin, "drop"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("frozen");
        assertThat(releases.scope(rel.getId())).containsExactlyInAnyOrder(a.getId(), b.getId());

        lifecycle.transition(rel.getId(), ReleaseState.RELEASED, "go", true, admin);
        assertThatThrownBy(() -> releases.commit(rel.getId(), c.getId(), admin, "late"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("released");
        assertThat(releases.scope(rel.getId())).hasSize(2);
    }

    // ------------------------------------------------------------------ gates

    @Test
    void VYB0928_AC5_theDefaultFreezeGatesRefuseAnEmptyScopeAndAnUnapprovedRequirementNamingEach() {
        Release rel = newRelease();
        lifecycle.transition(rel.getId(), ReleaseState.OPEN, null, false, admin);

        assertThatThrownBy(() -> lifecycle.transition(rel.getId(), ReleaseState.FROZEN, null, false, admin))
            .isInstanceOfSatisfying(ReleaseGateException.class, e -> assertThat(e.failed()).extracting(f -> f.gate())
                .containsExactly(ReleaseGate.SCOPE_NOT_EMPTY));

        Requirement draft = newRequirement(p, author); // still DRAFT
        releases.commit(rel.getId(), draft.getId(), admin, "in");
        assertThatThrownBy(() -> lifecycle.transition(rel.getId(), ReleaseState.FROZEN, null, false, admin))
            .isInstanceOfSatisfying(ReleaseGateException.class, e -> {
                assertThat(e.failed()).extracting(f -> f.gate()).containsExactly(ReleaseGate.ALL_APPROVED);
                assertThat(e.failed().get(0).detail()).contains("1 of 1").contains(draft.getKey());
            });
        assertThat(stateOf(rel)).isEqualTo(ReleaseState.OPEN);
        assertThat(lifecycle.history(rel.getId())).hasSize(1);
    }

    @Test
    void VYB0928_AC5_openCriticalGapsBlockFreezingAndTheReleaseGateAlsoCatchesBlockedItems() {
        Release rel = readyRelease();
        UUID req = releases.scope(rel.getId()).get(0);
        jdbc.update("""
            INSERT INTO finding (rule_key, fingerprint, object_type, object_id, severity, title)
            SELECT (SELECT key FROM gap_rule_template ORDER BY key LIMIT 1), ?, 'REQUIREMENT', ?, 'crit', 'A critical gap'""",
            UUID.randomUUID().toString(), req);
        assertThatThrownBy(() -> lifecycle.transition(rel.getId(), ReleaseState.FROZEN, null, false, admin))
            .isInstanceOfSatisfying(ReleaseGateException.class, e -> {
                assertThat(e.failed()).extracting(f -> f.gate()).containsExactly(ReleaseGate.NO_CRITICAL_GAPS);
                assertThat(e.failed().get(0).detail()).contains("1 open critical gap");
            });
        jdbc.update("UPDATE finding SET state = 'RESOLVED' WHERE object_id = ?", req);
        lifecycle.transition(rel.getId(), ReleaseState.FROZEN, null, false, admin);

        // releasing also checks that nothing in scope is blocked; remove the owner and it is
        jdbc.update("UPDATE requirement SET owner_id = NULL WHERE id = ?", req);
        assertThatThrownBy(() -> lifecycle.transition(rel.getId(), ReleaseState.RELEASED, null, false, admin))
            .isInstanceOfSatisfying(ReleaseGateException.class, e -> assertThat(e.failed()).extracting(f -> f.gate())
                .containsExactly(ReleaseGate.NO_BLOCKED_ITEMS));
    }

    @Test
    void VYB0928_AC6_aGateIsConfigurableOnOrOffAndTheVerifiedShareHasAThreshold() {
        Release rel = newRelease();
        lifecycle.transition(rel.getId(), ReleaseState.OPEN, null, false, admin);
        Requirement approvedNoTest = approvedRequirement();
        resolveFindings(approvedNoTest.getId());
        releases.commit(rel.getId(), approvedNoTest.getId(), admin, "in");

        // switched off, the approved requirement freezes; the verified-share gate on at 50% then refuses (0% verified)
        gateConfig.update(Transition.OPEN_TO_FROZEN, ReleaseGate.VERIFIED_SHARE, true, 50, admin);
        assertThatThrownBy(() -> lifecycle.transition(rel.getId(), ReleaseState.FROZEN, null, false, admin))
            .isInstanceOfSatisfying(ReleaseGateException.class, e -> {
                assertThat(e.failed()).extracting(f -> f.gate()).containsExactly(ReleaseGate.VERIFIED_SHARE);
                assertThat(e.failed().get(0).detail()).contains("0%").contains("at least 50%");
            });
        gateConfig.update(Transition.OPEN_TO_FROZEN, ReleaseGate.VERIFIED_SHARE, false, null, admin); // off; threshold kept
        assertThat(gateConfig.list()).anySatisfy(s -> {
            assertThat(s.gate()).isEqualTo(ReleaseGate.VERIFIED_SHARE);
            assertThat(s.transition()).isEqualTo(Transition.OPEN_TO_FROZEN);
            assertThat(s.enabled()).isFalse();
            assertThat(s.threshold()).isEqualTo(50);
        });
        lifecycle.transition(rel.getId(), ReleaseState.FROZEN, null, false, admin);

        // all gates off on the release move: anything goes
        for (ReleaseGate g : ReleaseGate.values()) gateConfig.update(Transition.FROZEN_TO_RELEASED, g, false, null, admin);
        lifecycle.transition(rel.getId(), ReleaseState.RELEASED, null, false, admin);
    }

    @Test
    void VYB0928_AC6_gateEditsAreValidatedAndAudited() {
        assertThatThrownBy(() -> gateConfig.update(Transition.OPEN_TO_FROZEN, ReleaseGate.ALL_APPROVED, true, 80, admin))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("verified-share");
        assertThatThrownBy(() -> gateConfig.update(Transition.OPEN_TO_FROZEN, ReleaseGate.VERIFIED_SHARE, true, 101, admin))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateConfig.update(Transition.OPEN_TO_FROZEN, ReleaseGate.VERIFIED_SHARE, true, -1, admin))
            .isInstanceOf(IllegalArgumentException.class);
        int before = jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'release-gate.updated'", Integer.class);
        gateConfig.update(Transition.FROZEN_TO_RELEASED, ReleaseGate.NO_BLOCKED_ITEMS, false, null, admin);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'release-gate.updated'", Integer.class)).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT (\"before\"->>'enabled') || '>' || (\"after\"->>'enabled') FROM audit_event "
            + "WHERE action = 'release-gate.updated' ORDER BY occurred_at DESC LIMIT 1", String.class)).isEqualTo("true>false");
        // the database holds the shape too: the threshold belongs to the verified-share gate only
        assertThatThrownBy(() -> jdbc.update("UPDATE release_gate SET threshold = 5 WHERE gate = 'ALL_APPROVED'"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // --------------------------------------------------------------- override

    @Test
    void VYB0928_AC7_aFailingGateCanBeOverriddenOnlyWithAReasonWhichIsRecordedWithTheGatesItOverrode() {
        Release rel = newRelease();
        lifecycle.transition(rel.getId(), ReleaseState.OPEN, null, false, admin);
        Requirement draft = newRequirement(p, author);
        releases.commit(rel.getId(), draft.getId(), admin, "in");

        assertThatThrownBy(() -> lifecycle.transition(rel.getId(), ReleaseState.FROZEN, "  ", true, admin))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");
        assertThat(stateOf(rel)).isEqualTo(ReleaseState.OPEN);

        lifecycle.transition(rel.getId(), ReleaseState.FROZEN, "the customer signed off the draft", true, admin);

        assertThat(stateOf(rel)).isEqualTo(ReleaseState.FROZEN);
        var t = lifecycle.history(rel.getId()).get(1);
        assertThat(t.overridden()).isTrue();
        assertThat(t.reason()).isEqualTo("the customer signed off the draft");
        assertThat(t.failedGates()).extracting(f -> f.gate()).containsExactly(ReleaseGate.ALL_APPROVED);
        assertThat(t.failedGates().get(0).detail()).contains(draft.getKey());
        assertThat(jdbc.queryForObject("SELECT \"after\"->>'overridden' FROM audit_event WHERE object_id = ? AND action = 'release.transitioned' "
            + "ORDER BY occurred_at DESC LIMIT 1", String.class, rel.getId())).isEqualTo("true");
    }

    @Test
    void VYB0928_AC7_overridingWhenNothingFailsIsNotRecordedAsAnOverride() {
        Release rel = readyRelease();
        lifecycle.transition(rel.getId(), ReleaseState.FROZEN, null, true, admin);
        assertThat(lifecycle.history(rel.getId()).get(1).overridden()).isFalse();
        assertThat(lifecycle.history(rel.getId()).get(1).failedGates()).isEmpty();
    }

    @Test
    void VYB0928_AC7_theDatabaseRefusesAnOverrideRecordedWithoutAReasonOrTheGates() {
        Release rel = newRelease();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO release_transition (release_id, from_state, to_state, overridden) VALUES (?, 'OPEN', 'FROZEN', true)", rel.getId()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void VYB0928_AC8_theOptionsShowEachMoveWithItsEnabledGatesEvaluated() {
        Release rel = newRelease();
        assertThat(lifecycle.options(rel.getId())).singleElement().satisfies(o -> {
            assertThat(o.to()).isEqualTo(ReleaseState.OPEN);
            assertThat(o.gates()).isEmpty(); // not a guarded move
            assertThat(o.ready()).isTrue();
        });
        lifecycle.transition(rel.getId(), ReleaseState.OPEN, null, false, admin);
        assertThat(lifecycle.options(rel.getId())).singleElement().satisfies(o -> {
            assertThat(o.to()).isEqualTo(ReleaseState.FROZEN);
            assertThat(o.ready()).isFalse();
            assertThat(o.gates()).extracting(g -> g.gate()).containsExactly(ReleaseGate.ALL_APPROVED, ReleaseGate.NO_CRITICAL_GAPS, ReleaseGate.SCOPE_NOT_EMPTY);
            assertThat(o.gates()).filteredOn(g -> !g.passed()).extracting(g -> g.gate()).containsExactly(ReleaseGate.SCOPE_NOT_EMPTY);
        });
        Release ready = readyRelease();
        lifecycle.transition(ready.getId(), ReleaseState.FROZEN, null, false, admin);
        assertThat(lifecycle.options(ready.getId())).extracting(o -> o.to()).containsExactlyInAnyOrder(ReleaseState.RELEASED, ReleaseState.OPEN);
        assertThat(lifecycle.options(ready.getId())).filteredOn(o -> o.to() == ReleaseState.OPEN).singleElement()
            .satisfies(o -> assertThat(o.needsReason()).isTrue());
    }

    @Test
    void VYB0928_AC9_movingAReleaseNeverChangesARequirementStatus() {
        Release rel = readyRelease();
        UUID req = releases.scope(rel.getId()).get(0);
        String before = jdbc.queryForObject("SELECT status FROM requirement WHERE id = ?", String.class, req);
        lifecycle.transition(rel.getId(), ReleaseState.FROZEN, null, false, admin);
        lifecycle.transition(rel.getId(), ReleaseState.RELEASED, null, false, admin);
        assertThat(jdbc.queryForObject("SELECT status FROM requirement WHERE id = ?", String.class, req)).isEqualTo(before);
    }

    // ------------------------------------------------------------------- HTTP

    private JwtRequestPostProcessor aPersonWith(AccessRole role, UUID capabilityId) {
        String id = unique("p");
        UUID user = users.upsert("sub-" + id, id + "@it.test", id).getId();
        grantOnCapability(user, role, capabilityId);
        return jwt().jwt(j -> j.subject("sub-" + id).claim("email", id + "@it.test").claim("preferred_username", id).claim("azp", "vyoog-web"));
    }

    private JwtRequestPostProcessor anAdministrator() {
        String id = unique("adm");
        UUID user = users.upsert("sub-" + id, id + "@it.test", id).getId();
        grants.grant(user, AccessRole.ADMINISTRATOR, com.vyoog.identity.ScopeType.PLATFORM, null, null, user);
        return jwt().jwt(j -> j.subject("sub-" + id).claim("email", id + "@it.test").claim("preferred_username", id).claim("azp", "vyoog-web"));
    }

    @Test
    void VYB0928_AC10_onlyAnApproverCanMoveAReleaseAndOnlyAnAdministratorCanEditTheGates() throws Exception {
        Release rel = newRelease();
        JwtRequestPostProcessor approver = aPersonWith(AccessRole.APPROVER, p.capabilityId());
        JwtRequestPostProcessor analyst = aPersonWith(AccessRole.BUSINESS_ANALYST, p.capabilityId());
        JwtRequestPostProcessor tester = aPersonWith(AccessRole.TESTER, p.capabilityId());
        String url = "/api/v1/releases/" + rel.getId() + "/transition";
        String open = "{\"to\":\"OPEN\"}";

        for (JwtRequestPostProcessor who : List.of(analyst, tester)) {
            mvc.perform(post(url).with(who).contentType(MediaType.APPLICATION_JSON).content(open)).andExpect(status().isForbidden());
        }
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(open)).andExpect(status().isUnauthorized());
        assertThat(stateOf(rel)).isEqualTo(ReleaseState.PLANNED);

        mvc.perform(post(url).with(approver).contentType(MediaType.APPLICATION_JSON).content(open))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("OPEN"));
        mvc.perform(post(url).with(approver).contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"BOGUS\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(url).with(approver).contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"RELEASED\"}")).andExpect(status().isConflict());

        String gate = "/api/v1/release-gates/OPEN_TO_FROZEN/VERIFIED_SHARE";
        String body = "{\"enabled\":false,\"threshold\":100}";
        for (JwtRequestPostProcessor who : List.of(approver, analyst, tester)) {
            mvc.perform(put(gate).with(who).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        }
        mvc.perform(put(gate).with(anAdministrator()).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"threshold\":80}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.threshold").value(80)).andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(put(gate).with(anAdministrator()).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"threshold\":500}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/release-gates/NOPE/VERIFIED_SHARE").with(anAdministrator()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/release-gates").with(analyst)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(10));
    }

    @Test
    void VYB0928_AC10_aRefusedMoveListsTheFailingGatesAndAnOverrideWithAReasonProceeds() throws Exception {
        Release rel = newRelease();
        lifecycle.transition(rel.getId(), ReleaseState.OPEN, null, false, admin);
        releases.commit(rel.getId(), newRequirement(p, author).getId(), admin, "in");
        JwtRequestPostProcessor approver = aPersonWith(AccessRole.APPROVER, p.capabilityId());
        String url = "/api/v1/releases/" + rel.getId() + "/transition";

        mvc.perform(post(url).with(approver).contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"FROZEN\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.failedGates[0].gate").value("ALL_APPROVED"))
            .andExpect(jsonPath("$.overridable").value(true));
        mvc.perform(get("/api/v1/releases/" + rel.getId() + "/gates").with(approver))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].to").value("FROZEN")).andExpect(jsonPath("$[0].ready").value(false));
        mvc.perform(post(url).with(approver).contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"FROZEN\",\"override\":true}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(url).with(approver).contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"FROZEN\",\"override\":true,\"reason\":\"accepted risk\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("FROZEN"));
        mvc.perform(get("/api/v1/releases/" + rel.getId() + "/history").with(approver))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[1].overridden").value(true)).andExpect(jsonPath("$[1].failedGates[0].gate").value("ALL_APPROVED"));
    }
}
