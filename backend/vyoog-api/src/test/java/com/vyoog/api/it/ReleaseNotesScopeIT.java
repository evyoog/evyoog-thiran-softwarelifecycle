package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vyoog.identity.AccessRole;
import com.vyoog.release.Release;
import com.vyoog.release.ReleaseLifecycleService;
import com.vyoog.release.ReleaseService;
import com.vyoog.release.ReleaseState;
import com.vyoog.requirements.Requirement;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0930 (F13): release notes as Markdown and Word, and the Scope tab's requirement picker: candidates, the
 * committed list with key and title, and committing several requirements with one reason, all or nothing.
 */
@AutoConfigureMockMvc
class ReleaseNotesScopeIT extends IntegrationTestBase {

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

    private JwtRequestPostProcessor aPersonWith(AccessRole role) {
        String id = unique("p");
        UUID user = users.upsert("sub-" + id, id + "@it.test", id).getId();
        grantOnCapability(user, role, p.capabilityId());
        return jwt().jwt(j -> j.subject("sub-" + id).claim("email", id + "@it.test").claim("preferred_username", id).claim("azp", "vyoog-web"));
    }

    private Requirement titled(String title) {
        return requirementService.create(title, "The system shall " + unique("x") + ".", "FUNCTIONAL", "MEDIUM",
            com.vyoog.requirements.Placement.capability(p.capabilityId()), author);
    }

    // ------------------------------------------------------------------ export

    @Test
    void VYB0930_AC1_theMarkdownExportHasTheNotesIncludingHeldItemsAndIsAFileDownload() throws Exception {
        Release rel = releases.create("Release " + unique("md") + " / Autumn");
        Requirement approvedReq = approved(titled("Invoice export"), author, admin);
        Requirement held = titled("Audit <trail> & more");
        releases.commit(rel.getId(), approvedReq.getId(), admin, "in");
        releases.commit(rel.getId(), held.getId(), admin, "in");

        var result = mvc.perform(get("/api/v1/releases/" + rel.getId() + "/notes/export").param("format", "markdown").with(aPersonWith(AccessRole.VIEWER)))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "text/markdown; charset=utf-8"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andReturn();
        String md = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String disposition = result.getResponse().getHeader("Content-Disposition");

        assertThat(disposition).startsWith("attachment").contains("-release-notes.md");
        String filename = disposition.replaceFirst("^.*filename=\"([^\"]+)\".*$", "$1");
        assertThat(filename).doesNotContain("/").doesNotContain(" ").endsWith("-release-notes.md"); // the release name is made safe for a file name
        assertThat(md).contains("# " + rel.getName().replace("_", "\\_") + " release notes", "- State: Planned", "## Approved",
            approvedReq.getKey() + " — Invoice export", "## Held — not approved", held.getKey() + " — Audit \\<trail\\> & more");
    }

    @Test
    void VYB0930_AC1_theWordExportIsAZipWhoseDocumentHoldsTheSameItems() throws Exception {
        Release rel = releases.create(unique("R"));
        Requirement a = approved(titled("Tax lines"), author, admin);
        Requirement h = titled("Held one");
        releases.commit(rel.getId(), a.getId(), admin, "in");
        releases.commit(rel.getId(), h.getId(), admin, "in");

        var result = mvc.perform(get("/api/v1/releases/" + rel.getId() + "/notes/export").param("format", "docx").with(aPersonWith(AccessRole.VIEWER)))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            .andReturn();
        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("-release-notes.docx");

        String document = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if (e.getName().equals("word/document.xml")) document = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        assertThat(document).contains(a.getKey() + " — Tax lines", h.getKey() + " — Held one", "Held — not approved");
    }

    @Test
    void VYB0930_AC1_anUnknownFormatIsRefusedAnUnknownReleaseIs404AndNoTokenIs401() throws Exception {
        Release rel = releases.create(unique("R"));
        JwtRequestPostProcessor viewer = aPersonWith(AccessRole.VIEWER);
        mvc.perform(get("/api/v1/releases/" + rel.getId() + "/notes/export").param("format", "pdf").with(viewer)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/releases/" + rel.getId() + "/notes/export").with(viewer)).andExpect(status().is4xxClientError()); // format is required
        mvc.perform(get("/api/v1/releases/" + UUID.randomUUID() + "/notes/export").param("format", "docx").with(viewer)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/releases/" + rel.getId() + "/notes/export").param("format", "docx")).andExpect(status().isUnauthorized());
    }

    @Test
    void VYB0930_AC1_aReleaseWithNothingCommittedStillExportsAndSaysSo() throws Exception {
        Release rel = releases.create(unique("R"));
        String md = mvc.perform(get("/api/v1/releases/" + rel.getId() + "/notes/export").param("format", "md").with(aPersonWith(AccessRole.VIEWER)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(md).contains("Nothing is committed to this release.");
    }

    // ------------------------------------------------------------- committed list

    @Test
    void VYB0930_AC2_theCommittedListShowsKeyTitleStatusAndCapabilityNotIds() {
        Release rel = releases.create(unique("R"));
        Requirement a = approved(titled("Zeta item"), author, admin);
        Requirement b = titled("Alpha item");
        releases.commit(rel.getId(), a.getId(), admin, "in");
        releases.commit(rel.getId(), b.getId(), admin, "in");

        var items = releases.scopeItems(rel.getId());

        assertThat(items).extracting(ReleaseService.ScopeItem::key).isSortedAccordingTo(String::compareTo).containsExactlyInAnyOrder(a.getKey(), b.getKey());
        assertThat(items).filteredOn(i -> i.key().equals(a.getKey())).singleElement().satisfies(i -> {
            assertThat(i.title()).isEqualTo("Zeta item");
            assertThat(i.status()).isEqualTo("APPROVED");
            assertThat(i.capabilityName()).isNotBlank();
        });
        assertThatThrownBy(() -> releases.scopeItems(UUID.randomUUID())).isInstanceOf(java.util.NoSuchElementException.class);
    }

    // ----------------------------------------------------------------- candidates

    @Test
    void VYB0930_AC3_theCandidatesAreSearchableByKeyOrTitleAndSayWhichReleaseAlreadyHoldsOne() {
        String tag = unique("Needle");
        Release here = releases.create(unique("Here")), other = releases.create(unique("Other"));
        Requirement free = titled(tag + " free"), mine = titled(tag + " mine"), taken = titled(tag + " taken");
        releases.commit(here.getId(), mine.getId(), admin, "in");
        releases.commit(other.getId(), taken.getId(), admin, "in");

        var page = releases.candidates(here.getId(), tag, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(ReleaseService.Candidate::key).containsExactly(free.getKey(), mine.getKey(), taken.getKey());
        assertThat(page.getContent().get(0).committedToId()).isNull();
        assertThat(page.getContent().get(1).committedToId()).isEqualTo(here.getId().toString());
        assertThat(page.getContent().get(2).committedToId()).isEqualTo(other.getId().toString());
        assertThat(page.getContent().get(2).committedToName()).isEqualTo(other.getName());
        // by key, case-insensitively
        assertThat(releases.candidates(here.getId(), free.getKey().toLowerCase(), PageRequest.of(0, 10)).getContent())
            .extracting(ReleaseService.Candidate::key).containsExactly(free.getKey());
        // paging
        assertThat(releases.candidates(here.getId(), tag, PageRequest.of(1, 2)).getContent()).hasSize(1);
        assertThat(releases.candidates(here.getId(), tag, PageRequest.of(0, 2)).getTotalPages()).isEqualTo(2);
    }

    @Test
    void VYB0930_AC3_wildcardsInTheSearchAreLiteralAndADeletedRequirementIsNotOffered() {
        Release rel = releases.create(unique("R"));
        Requirement r = titled("Percent 100% done");
        titled("Percent 100 done");
        assertThat(releases.candidates(rel.getId(), "100%", PageRequest.of(0, 10)).getContent())
            .extracting(ReleaseService.Candidate::key).containsExactly(r.getKey());
        assertThat(releases.candidates(rel.getId(), "%_" + unique("zz"), PageRequest.of(0, 10)).getContent()).isEmpty();

        jdbc.update("UPDATE requirement SET deleted_at = now() WHERE id = ?", r.getId());
        assertThat(releases.candidates(rel.getId(), "100%", PageRequest.of(0, 10)).getContent()).isEmpty();
        assertThatThrownBy(() -> releases.candidates(UUID.randomUUID(), "x", PageRequest.of(0, 10))).isInstanceOf(java.util.NoSuchElementException.class);
    }

    // -------------------------------------------------------------- bulk commit

    @Test
    void VYB0930_AC4_severalRequirementsAreCommittedWithOneReasonEachLeavingItsOwnMovementAndAuditEvent() {
        Release rel = releases.create(unique("R"));
        Requirement a = titled("A"), b = titled("B"), c = titled("C");

        var result = releases.commitAll(rel.getId(), List.of(a.getId(), b.getId(), c.getId(), a.getId()), admin, "release planning");

        assertThat(result.committed()).containsExactly(a.getId(), b.getId(), c.getId()); // a duplicate in the request counts once
        assertThat(result.alreadyCommitted()).isEmpty();
        assertThat(releases.scope(rel.getId())).containsExactlyInAnyOrder(a.getId(), b.getId(), c.getId());
        assertThat(releases.movements(rel.getId(), Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600)))
            .hasSize(3).allSatisfy(m -> assertThat(m.getReason()).isEqualTo("release planning"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE object_id = ? AND action = 'release.scope_added'", Integer.class, rel.getId())).isEqualTo(3);
    }

    @Test
    void VYB0930_AC4_aRequirementAlreadyHereIsSkippedAndReportedNotAnError() {
        Release rel = releases.create(unique("R"));
        Requirement a = titled("A"), b = titled("B");
        releases.commit(rel.getId(), a.getId(), admin, "first");

        var result = releases.commitAll(rel.getId(), List.of(a.getId(), b.getId()), admin, "second");

        assertThat(result.committed()).containsExactly(b.getId());
        assertThat(result.alreadyCommitted()).containsExactly(a.getId());
        assertThat(releases.movements(rel.getId(), Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600))).hasSize(2); // not three
    }

    @Test
    void VYB0930_AC5_oneRequirementTakenByAnotherReleaseRefusesTheWholeCallNamingItAndCommitsNothing() {
        Release rel = releases.create(unique("R")), other = releases.create(unique("O"));
        Requirement free = titled("Free"), taken = titled("Taken");
        releases.commit(other.getId(), taken.getId(), admin, "elsewhere");

        assertThatThrownBy(() -> releases.commitAll(rel.getId(), List.of(free.getId(), taken.getId()), admin, "go"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining(taken.getKey()).hasMessageContaining("Nothing was committed");

        assertThat(releases.scope(rel.getId())).isEmpty(); // the free one was not committed either
        assertThat(releases.scope(other.getId())).containsExactly(taken.getId());
        assertThat(releases.movements(rel.getId(), Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600))).isEmpty();
    }

    @Test
    void VYB0930_AC5_aMissingRequirementOrAMissingReasonOrAnEmptyListRefusesTheWholeCall() {
        Release rel = releases.create(unique("R"));
        Requirement a = titled("A");
        assertThatThrownBy(() -> releases.commitAll(rel.getId(), List.of(a.getId(), UUID.randomUUID()), admin, "go"))
            .isInstanceOf(java.util.NoSuchElementException.class);
        assertThatThrownBy(() -> releases.commitAll(rel.getId(), List.of(a.getId()), admin, "  ")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> releases.commitAll(rel.getId(), List.of(), admin, "go")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> releases.commitAll(rel.getId(), null, admin, "go")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> releases.commitAll(UUID.randomUUID(), List.of(a.getId()), admin, "go")).isInstanceOf(java.util.NoSuchElementException.class);
        jdbc.update("UPDATE requirement SET deleted_at = now() WHERE id = ?", a.getId());
        assertThatThrownBy(() -> releases.commitAll(rel.getId(), List.of(a.getId()), admin, "go")).isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(releases.scope(rel.getId())).isEmpty();
    }

    @Test
    void VYB0930_AC5_aLockedScopeRefusesTheBulkCommitToo() {
        Release rel = releases.create(unique("R"));
        lifecycle.transition(rel.getId(), ReleaseState.OPEN, null, false, admin);
        Requirement ready = approved(titled("Ready"), author, admin);
        releases.commit(rel.getId(), ready.getId(), admin, "in");
        lifecycle.transition(rel.getId(), ReleaseState.FROZEN, "go", true, admin, new ReleaseLifecycleService.Signature("step-up", null));
        Requirement late = titled("Late");

        assertThatThrownBy(() -> releases.commitAll(rel.getId(), List.of(late.getId()), admin, "go"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("frozen");
        assertThat(releases.scope(rel.getId())).containsExactly(ready.getId());
    }

    @Test
    void VYB0930_AC5_atMostTwoHundredAtATime() {
        Release rel = releases.create(unique("R"));
        List<UUID> many = java.util.stream.IntStream.range(0, 201).mapToObj(i -> UUID.randomUUID()).toList();
        assertThatThrownBy(() -> releases.commitAll(rel.getId(), many, admin, "go")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("200");
    }

    // ------------------------------------------------------------------- HTTP

    @Test
    void VYB0930_AC6_onlyAnApproverCanBulkCommitAndEveryoneSignedInCanReadTheCandidatesAndTheList() throws Exception {
        Release rel = releases.create(unique("R"));
        Requirement a = titled("Http A"), b = titled("Http B");
        JwtRequestPostProcessor approver = aPersonWith(AccessRole.APPROVER);
        JwtRequestPostProcessor analyst = aPersonWith(AccessRole.BUSINESS_ANALYST);
        JwtRequestPostProcessor tester = aPersonWith(AccessRole.TESTER);
        String url = "/api/v1/releases/" + rel.getId() + "/scope/bulk";
        String body = "{\"requirementIds\":[\"" + a.getId() + "\",\"" + b.getId() + "\"],\"reason\":\"planning\"}";

        for (JwtRequestPostProcessor who : List.of(analyst, tester)) {
            mvc.perform(post(url).with(who).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        }
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        assertThat(releases.scope(rel.getId())).isEmpty();

        mvc.perform(post(url).with(approver).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.committed.length()").value(2)).andExpect(jsonPath("$.alreadyCommitted.length()").value(0));
        mvc.perform(post(url).with(approver).contentType(MediaType.APPLICATION_JSON).content("{\"requirementIds\":[],\"reason\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(url).with(approver).contentType(MediaType.APPLICATION_JSON).content("{\"requirementIds\":[\"" + a.getId() + "\"],\"reason\":\" \"}")).andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/releases/" + rel.getId() + "/scope/items").with(tester))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].key").exists()).andExpect(jsonPath("$[0].title").exists());
        mvc.perform(get("/api/v1/releases/" + rel.getId() + "/candidates").param("q", a.getKey()).with(tester))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].key").value(a.getKey()))
            .andExpect(jsonPath("$.content[0].committedToId").value(rel.getId().toString()));
    }

    @Test
    void VYB0930_AC6_aRefusalOverHttpIsAConflictNamingTheRequirementAndNothingIsCommitted() throws Exception {
        Release rel = releases.create(unique("R")), other = releases.create(unique("O"));
        Requirement free = titled("Free"), taken = titled("Taken");
        releases.commit(other.getId(), taken.getId(), admin, "elsewhere");

        mvc.perform(post("/api/v1/releases/" + rel.getId() + "/scope/bulk").with(aPersonWith(AccessRole.APPROVER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirementIds\":[\"" + free.getId() + "\",\"" + taken.getId() + "\"],\"reason\":\"go\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(taken.getKey())));
        assertThat(releases.scope(rel.getId())).isEmpty();
    }
}
