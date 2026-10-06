package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vyoog.identity.AccessRole;
import com.vyoog.release.Release;
import com.vyoog.release.ReleaseLifecycleService;
import com.vyoog.release.ReleaseService;
import com.vyoog.release.ReleaseState;
import com.vyoog.requirements.Requirement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0929 (F13): freezing and releasing are signature events (spec 4.5): step-up authentication at the moment of the
 * move, the level achieved recorded with it; and the release being prepared that the Home panel reads.
 */
@AutoConfigureMockMvc
class ReleaseSignOffIT extends IntegrationTestBase {

    @Autowired ReleaseService releases;
    @Autowired ReleaseLifecycleService lifecycle;
    @Autowired MockMvc mvc;

    private Portfolio p;
    private UUID author, admin;

    @BeforeEach
    void fixtures() {
        p = newPortfolio();
        author = newUser("author");
        admin = newAdministrator();
    }

    private Release openRelease() {
        Release rel = releases.create(unique("R"));
        lifecycle.transition(rel.getId(), ReleaseState.OPEN, null, false, admin);
        return rel;
    }

    /** The approver's token; {@code acr} null means a plain sign-in with no step-up. */
    private JwtRequestPostProcessor approver(String acr, Long authTime) {
        String id = unique("appr");
        UUID user = users.upsert("sub-" + id, id + "@it.test", id).getId();
        grantOnCapability(user, AccessRole.APPROVER, p.capabilityId());
        return jwt().jwt(j -> {
            j.subject("sub-" + id).claim("email", id + "@it.test").claim("preferred_username", id).claim("azp", "vyoog-web");
            if (acr != null) j.claim("acr", acr);
            if (authTime != null) j.claim("auth_time", authTime);
        });
    }

    private String body(String to) {
        return "{\"to\":\"" + to + "\",\"override\":true,\"reason\":\"signed in the test\"}";
    }

    // --------------------------------------------------------------- the domain

    @Test
    void VYB0929_AC1_movingIntoFrozenOrReleasedWithoutASignatureIsRefusedAndOpeningNeedsNone() {
        Release rel = releases.create(unique("R"));
        lifecycle.transition(rel.getId(), ReleaseState.OPEN, null, false, admin); // no signature: fine
        assertThatThrownBy(() -> lifecycle.transition(rel.getId(), ReleaseState.FROZEN, "x", true, admin))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("signature event");
        assertThatThrownBy(() -> lifecycle.transition(rel.getId(), ReleaseState.FROZEN, "x", true, admin,
            new ReleaseLifecycleService.Signature(" ", null))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT state FROM release WHERE id = ?", String.class, rel.getId())).isEqualTo("OPEN");
        assertThat(lifecycle.history(rel.getId())).hasSize(1); // refused moves leave nothing
    }

    @Test
    void VYB0929_AC2_aSignedMoveRecordsTheLevelAchievedAndWhenThePersonAuthenticatedAndAnUnsignedOneRecordsNone() {
        Release rel = openRelease();
        Instant authenticated = Instant.parse("2026-10-06T08:30:00Z");

        lifecycle.transition(rel.getId(), ReleaseState.FROZEN, "go", true, admin, new ReleaseLifecycleService.Signature("step-up", authenticated));
        lifecycle.transition(rel.getId(), ReleaseState.OPEN, "reopen", false, admin, new ReleaseLifecycleService.Signature("step-up", authenticated));

        var history = lifecycle.history(rel.getId());
        assertThat(history).extracting(t -> t.from() + ">" + t.to()).containsExactly("PLANNED>OPEN", "OPEN>FROZEN", "FROZEN>OPEN");
        assertThat(history.get(0).signatureAcr()).isNull();            // opening: not a signature event
        assertThat(history.get(1).signatureAcr()).isEqualTo("step-up");
        assertThat(history.get(1).authTime()).isEqualTo(authenticated);
        assertThat(history.get(1).changedBy()).isEqualTo(admin);
        assertThat(history.get(2).signatureAcr()).isNull();            // a signature handed to a reopen is not recorded
        assertThat(jdbc.queryForObject("SELECT \"after\"->>'signatureAcr' FROM audit_event WHERE object_id = ? AND action = 'release.transitioned' "
            + "AND \"after\"->>'state' = 'FROZEN'", String.class, rel.getId())).isEqualTo("step-up");
    }

    @Test
    void VYB0929_AC2_theAuthenticationTimeMayBeUnknownButTheLevelNeverIs() {
        Release rel = openRelease();
        lifecycle.transition(rel.getId(), ReleaseState.FROZEN, "go", true, admin, new ReleaseLifecycleService.Signature("step-up", null));
        var t = lifecycle.history(rel.getId()).get(1);
        assertThat(t.signatureAcr()).isEqualTo("step-up");
        assertThat(t.authTime()).isNull();
    }

    @Test
    void VYB0929_AC3_theDatabaseRefusesASignedMoveRecordedWithNoLevel() {
        Release rel = releases.create(unique("R"));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO release_transition (release_id, from_state, to_state) VALUES (?, 'OPEN', 'FROZEN')", rel.getId()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO release_transition (release_id, from_state, to_state) VALUES (?, 'FROZEN', 'RELEASED')", rel.getId()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO release_transition (release_id, from_state, to_state) VALUES (?, 'PLANNED', 'OPEN')", rel.getId()); // not signed: fine
    }

    @Test
    void VYB0929_AC4_theOptionsSaySignaturesAreRequiredForFreezeAndReleaseOnly() {
        Release rel = releases.create(unique("R"));
        assertThat(lifecycle.options(rel.getId())).singleElement().satisfies(o -> assertThat(o.signatureRequired()).isFalse()); // to OPEN
        lifecycle.transition(rel.getId(), ReleaseState.OPEN, null, false, admin);
        assertThat(lifecycle.options(rel.getId())).singleElement().satisfies(o -> assertThat(o.signatureRequired()).isTrue());    // to FROZEN
        lifecycle.transition(rel.getId(), ReleaseState.FROZEN, "go", true, admin, new ReleaseLifecycleService.Signature("step-up", null));
        assertThat(lifecycle.options(rel.getId())).allSatisfy(o -> assertThat(o.signatureRequired()).isEqualTo(o.to() == ReleaseState.RELEASED));
    }

    // ----------------------------------------------------------------- over HTTP

    @Test
    void VYB0929_AC5_withoutStepUpFreezingAndReleasingGet401NamingTheLevelAndNothingMoves() throws Exception {
        Release rel = openRelease();
        String url = "/api/v1/releases/" + rel.getId() + "/transition";

        mvc.perform(post(url).with(approver(null, null)).contentType(MediaType.APPLICATION_JSON).content(body("FROZEN")))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.requiredLevel").value("step-up"));
        mvc.perform(post(url).with(approver("password", null)).contentType(MediaType.APPLICATION_JSON).content(body("FROZEN")))
            .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT state FROM release WHERE id = ?", String.class, rel.getId())).isEqualTo("OPEN");
        assertThat(lifecycle.history(rel.getId())).hasSize(1);
    }

    @Test
    void VYB0929_AC5_withStepUpTheMoveSucceedsAndTheLevelAndAuthTimeAreRecorded() throws Exception {
        Release rel = openRelease();
        String url = "/api/v1/releases/" + rel.getId() + "/transition";
        long authTime = Instant.parse("2026-10-06T09:00:00Z").getEpochSecond();

        mvc.perform(post(url).with(approver("step-up", authTime)).contentType(MediaType.APPLICATION_JSON).content(body("FROZEN")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("FROZEN"));
        mvc.perform(get("/api/v1/releases/" + rel.getId() + "/history").with(approver(null, null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[1].signatureAcr").value("step-up"))
            .andExpect(jsonPath("$[1].authTime").value("2026-10-06T09:00:00Z"))
            .andExpect(jsonPath("$[0].signatureAcr").doesNotExist());
        mvc.perform(post(url).with(approver("step-up", authTime)).contentType(MediaType.APPLICATION_JSON).content(body("RELEASED")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RELEASED"));
    }

    @Test
    void VYB0929_AC5_openingAndReopeningNeedNoStepUp() throws Exception {
        Release rel = releases.create(unique("R"));
        String url = "/api/v1/releases/" + rel.getId() + "/transition";
        mvc.perform(post(url).with(approver(null, null)).contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"OPEN\"}"))
            .andExpect(status().isOk());
        lifecycle.transition(rel.getId(), ReleaseState.FROZEN, "go", true, admin, new ReleaseLifecycleService.Signature("step-up", null));
        mvc.perform(post(url).with(approver(null, null)).contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"OPEN\",\"reason\":\"a late change\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("OPEN"));
    }

    @Test
    void VYB0929_AC5_aServiceAccountTokenCannotSignAndStepUpDoesNotReplaceTheApproverRole() throws Exception {
        Release rel = openRelease();
        String url = "/api/v1/releases/" + rel.getId() + "/transition";
        // a client-credentials token: no person behind it
        mvc.perform(post(url).with(jwt().jwt(j -> j.subject("svc").claim("azp", "ci-bot").claim("acr", "step-up")))
                .contentType(MediaType.APPLICATION_JSON).content(body("FROZEN")))
            .andExpect(status().is4xxClientError());
        // a Tester with step-up still is not an approver
        String id = unique("tst");
        UUID user = users.upsert("sub-" + id, id + "@it.test", id).getId();
        grantOnCapability(user, AccessRole.TESTER, p.capabilityId());
        mvc.perform(post(url).with(jwt().jwt(j -> j.subject("sub-" + id).claim("email", id + "@it.test").claim("preferred_username", id)
                .claim("azp", "vyoog-web").claim("acr", "step-up"))).contentType(MediaType.APPLICATION_JSON).content(body("FROZEN")))
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT state FROM release WHERE id = ?", String.class, rel.getId())).isEqualTo("OPEN");
    }

    // ------------------------------------------------ the release being prepared

    /** The picture is global (the nearest release being prepared), so each test starts with none being prepared. */
    private void noOtherReleaseBeingPrepared() {
        jdbc.update("UPDATE release SET state = 'RELEASED' WHERE state IN ('OPEN', 'FROZEN')");
    }

    @Test
    void VYB0929_AC6_theReleaseBeingPreparedIsTheNearestOpenOrFrozenOneAndNoneWhenNoneIs() throws Exception {
        noOtherReleaseBeingPrepared();
        assertThat(lifecycle.current()).isEmpty();
        releases.create(unique("planned-only")); // PLANNED is not being prepared

        mvc.perform(get("/api/v1/releases/current").with(approver(null, null))).andExpect(status().isNoContent());
        assertThat(lifecycle.current()).isEmpty();

        Release later = openRelease();
        Release undated = openRelease();
        Release sooner = openRelease();
        Release frozenSoonest = openRelease();
        releases.setTargetDate(later.getId(), Instant.parse("2027-03-01T00:00:00Z"), admin);
        releases.setTargetDate(sooner.getId(), Instant.parse("2027-01-01T00:00:00Z"), admin);
        releases.setTargetDate(frozenSoonest.getId(), Instant.parse("2026-12-01T00:00:00Z"), admin);
        lifecycle.transition(frozenSoonest.getId(), ReleaseState.FROZEN, "go", true, admin, new ReleaseLifecycleService.Signature("step-up", null));

        assertThat(lifecycle.current().orElseThrow().release().getId()).isEqualTo(frozenSoonest.getId()); // FROZEN counts, earliest date wins
        lifecycle.transition(frozenSoonest.getId(), ReleaseState.RELEASED, "go", true, admin, new ReleaseLifecycleService.Signature("step-up", null));
        assertThat(lifecycle.current().orElseThrow().release().getId()).isEqualTo(sooner.getId());
        releases.setTargetDate(sooner.getId(), Instant.parse("2028-01-01T00:00:00Z"), admin);
        releases.setTargetDate(later.getId(), Instant.parse("2028-02-01T00:00:00Z"), admin);
        assertThat(lifecycle.current().orElseThrow().release().getId()).isEqualTo(sooner.getId());
        jdbc.update("UPDATE release SET state = 'RELEASED' WHERE id IN (?, ?)", sooner.getId(), later.getId());
        assertThat(lifecycle.current().orElseThrow().release().getId()).isEqualTo(undated.getId()); // a release with no date sorts last
    }

    @Test
    void VYB0929_AC7_theCurrentReleaseCarriesItsNextMovesGatesAndItsBlockedRequirements() throws Exception {
        noOtherReleaseBeingPrepared();
        Release rel = openRelease();
        Requirement draft = newRequirement(p, author); // not Approved, no test, no owner: blocked and failing ALL_APPROVED
        releases.commit(rel.getId(), draft.getId(), admin, "in");
        releases.setTargetDate(rel.getId(), Instant.parse("2027-02-02T00:00:00Z"), admin);

        mvc.perform(get("/api/v1/releases/current").with(approver(null, null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(rel.getId().toString()))
            .andExpect(jsonPath("$.state").value("OPEN"))
            .andExpect(jsonPath("$.targetDate").value("2027-02-02T00:00:00Z"))
            .andExpect(jsonPath("$.moves[0].to").value("FROZEN"))
            .andExpect(jsonPath("$.moves[0].signatureRequired").value(true))
            .andExpect(jsonPath("$.moves[0].ready").value(false))
            .andExpect(jsonPath("$.moves[0].gates[?(@.gate=='ALL_APPROVED')].passed").value(false))
            .andExpect(jsonPath("$.blocked[0].key").value(draft.getKey()))
            .andExpect(jsonPath("$.blocked[0].reason").value("unverified"));
        mvc.perform(get("/api/v1/releases/current")).andExpect(status().isUnauthorized());
    }
}
