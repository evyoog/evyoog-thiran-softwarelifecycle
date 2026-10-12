package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vyoog.baseline.Baseline;
import com.vyoog.baseline.BaselineService;
import com.vyoog.baseline.EvidencePackService;
import com.vyoog.design.DesignFlow;
import com.vyoog.design.DesignService;
import com.vyoog.design.NodeKind;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.ScopeType;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementStatus;
import com.vyoog.trace.TraceGraphService;
import com.vyoog.trace.TraceLinkType;
import com.vyoog.trace.TraceObjectType;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0948 (F41, D39): the evidence pack for a baseline, from real data: what it holds, that it reads the frozen revision and not
 * the current one, who may take it, and that each export is audited. Against a real PostgreSQL, service calls and HTTP with real tokens.
 */
@AutoConfigureMockMvc
class EvidencePackIT extends IntegrationTestBase {

    @Autowired EvidencePackService packs;
    @Autowired BaselineService baselines;
    @Autowired DesignService design;
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

    private Baseline freeze(Requirement... rs) {
        return baselines.freeze(unique("BL"), null, java.util.Arrays.stream(rs).map(Requirement::getId).toList(), admin);
    }

    private Map<String, String> files(byte[] zip) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                byte[] b = in.readAllBytes();
                out.put(e.getName(), e.getName().endsWith(".docx") ? "(docx)" : new String(b, StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private UUID testFor(Requirement r) {
        UUID t = jdbc.queryForObject("INSERT INTO test_case (key, title) VALUES (?, ?) RETURNING id", UUID.class, unique("TC"), unique("Test"));
        trace.createLink(TraceObjectType.TEST, t, TraceObjectType.REQUIREMENT, r.getId(), TraceLinkType.VERIFIES, author);
        return t;
    }

    private void result(Requirement r, int revision, UUID test, String result, Instant at) {
        jdbc.update("INSERT INTO verification (requirement_id, requirement_revision, test_case_id, result, verified_at) VALUES (?, ?, ?, ?, ?)",
            r.getId(), revision, test, result, Timestamp.from(at));
    }

    private Instant frozenAt(Baseline b) {
        return jdbc.queryForObject("SELECT frozen_at FROM baseline WHERE id = ?", Timestamp.class, b.getId()).toInstant();
    }

    @Test
    void VYB0948_AC10_thePackHoldsEachFrozenRequirementWithItsStatusDesignTestsResultsAndApprovals() throws Exception {
        Requirement approved = approved(newRequirement(p, author), author, admin);
        Requirement draft = newRequirement(p, author);
        DesignFlow flow = design.createFlow(p.applicationId(), author);
        UUID node = design.addNode(flow.getId(), NodeKind.step, "Export invoice", null, author).getId();
        design.linkRequirement(node, approved.getId(), author);
        UUID test = testFor(approved);
        result(approved, approved.getRevision(), test, "PASS", Instant.now().minusSeconds(3600));

        Baseline base = freeze(approved, draft);
        var pack = packs.assemble(base.getId());

        assertThat(pack.baseline().name()).isEqualTo(base.getName());
        assertThat(pack.baseline().items()).isEqualTo(2);
        assertThat(pack.requirements()).extracting(r -> r.key() + ":" + r.statusAtFreeze())
            .containsExactly(approved.getKey() + ":APPROVED", draft.getKey() + ":DRAFT");
        assertThat(pack.requirements().get(0).statement()).isEqualTo(approved.getStatement());
        assertThat(pack.requirements().get(0).product()).isNotNull();
        assertThat(pack.design()).singleElement().satisfies(d -> {
            assertThat(d.requirementKey()).isEqualTo(approved.getKey());
            assertThat(d.nodeLabel()).isEqualTo("Export invoice");
        });
        assertThat(pack.tests()).singleElement().satisfies(t -> assertThat(t.requirementKey()).isEqualTo(approved.getKey()));
        assertThat(pack.results()).singleElement().satisfies(r -> {
            assertThat(r.result()).isEqualTo("PASS");
            assertThat(r.atOrBeforeFreeze()).isTrue();
        });
        assertThat(pack.statusChanges()).extracting(c -> c.fromStatus() + ">" + c.toStatus())
            .containsExactly("DRAFT>IN_REVIEW", "IN_REVIEW>REVIEWED", "REVIEWED>APPROVED");
        assertThat(pack.statusChanges()).allSatisfy(c -> {
            assertThat(c.atOrBeforeFreeze()).isTrue();
            assertThat(c.actor()).isNotBlank();
        });
    }

    @Test
    void VYB0948_AC11_aChangeAfterTheFreezeLeavesTheFrozenWordingAndStatusAndIsMarkedAsAfter() throws Exception {
        Requirement r = newRequirement(p, author);
        String frozenTitle = r.getTitle(), frozenStatement = r.getStatement();
        Baseline base = freeze(r);

        Requirement edited = requirementService.update(r.getId(), r.getRevision(), "A new title after the freeze", "The system shall be different.",
            r.getType(), r.getPriority(), r.getCapabilityId(), author);
        requirementService.transition(edited.getId(), edited.getRevision(), RequirementStatus.IN_REVIEW, "submitted after the freeze", author);

        var pack = packs.assemble(base.getId());
        var row = pack.requirements().get(0);
        assertThat(row.frozenRevision()).isEqualTo(r.getRevision());
        assertThat(row.title()).isEqualTo(frozenTitle);
        assertThat(row.statement()).isEqualTo(frozenStatement);
        assertThat(row.statusAtFreeze()).isEqualTo("DRAFT");
        assertThat(row.currentStatus()).isEqualTo("IN_REVIEW");
        assertThat(row.currentRevision()).isEqualTo(edited.getRevision());
        assertThat(row.changedSinceFreeze()).isTrue();
        assertThat(pack.statusChanges()).singleElement().satisfies(c -> {
            assertThat(c.atOrBeforeFreeze()).isFalse();
            assertThat(c.reason()).isEqualTo("submitted after the freeze");
        });
    }

    @Test
    void VYB0948_AC12_onlyResultsForTheFrozenRevisionAreInAndTheyAreFlaggedBeforeOrAfterTheFreeze() throws Exception {
        Requirement r = newRequirement(p, author);
        UUID test = testFor(r);
        int rev = r.getRevision();
        Baseline base = freeze(r);
        Instant frozen = frozenAt(base);
        result(r, rev, test, "FAIL", frozen.minusSeconds(7200));
        result(r, rev, test, "PASS", frozen.plusSeconds(7200));
        result(r, rev + 1, test, "PASS", frozen.plusSeconds(10));          // another revision: not this baseline's evidence
        result(r, rev - 1, test, "PASS", frozen.minusSeconds(10));

        var results = packs.assemble(base.getId()).results();

        assertThat(results).extracting(x -> x.result() + ":" + x.atOrBeforeFreeze()).containsExactly("FAIL:true", "PASS:false");
        assertThat(results).allSatisfy(x -> assertThat(x.requirementRevision()).isEqualTo(rev));
    }

    @Test
    void VYB0948_AC13_theBundleIsAZipWithEveryFileAndAnExportIsAuditedWithWhoTookIt() throws Exception {
        Requirement r = approved(newRequirement(p, author), author, admin);
        Baseline base = freeze(r);

        var export = packs.export(base.getId(), admin, "Ada Admin");
        Map<String, String> files = files(export.bytes());

        assertThat(files.keySet()).containsExactly("README.txt", "baseline.csv", "requirements.csv", "design.csv", "tests.csv", "results.csv",
            "approvals.csv", "report.docx", "SHA256SUMS", "manifest.json");
        assertThat(files.get("requirements.csv")).contains(r.getKey()).contains("APPROVED");
        assertThat(files.get("manifest.json")).contains("\"generatedBy\" : \"Ada Admin\"").contains(base.getName());
        assertThat(export.filename()).startsWith("evidence-pack-").endsWith(".zip");
        assertThat(auditCount(base.getId(), "baseline.evidence_pack_exported")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT (after->>'requirements')::int FROM audit_event WHERE object_id = ? AND action = 'baseline.evidence_pack_exported'",
            Integer.class, base.getId())).isEqualTo(1);
        assertThatThrownBy(() -> packs.export(UUID.randomUUID(), admin, "Ada Admin")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void VYB0948_AC14_aRequirementDeletedAfterTheFreezeIsStillInThePackAndSaysSo() throws Exception {
        Requirement r = newRequirement(p, author);
        Baseline base = freeze(r);
        jdbc.update("UPDATE requirement SET deleted_at = now() WHERE id = ?", r.getId());

        var row = packs.assemble(base.getId()).requirements().get(0);

        assertThat(row.key()).isEqualTo(r.getKey());
        assertThat(row.deleted()).isTrue();
        assertThat(row.statement()).isEqualTo(r.getStatement());
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
    void VYB0948_AC15_whoMayTakeAPackOverHttpApproverComplianceLeadArchitectAndAdministratorNotAReviewerAnalystViewerOrNoOne() throws Exception {
        Baseline base = freeze(newRequirement(p, author));
        String url = "/api/v1/baselines/" + base.getId() + "/evidence-pack";

        for (AccessRole allowed : List.of(AccessRole.APPROVER, AccessRole.COMPLIANCE_LEAD, AccessRole.ARCHITECT)) {
            mvc.perform(get(url).with(aPersonWith(allowed, false))).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }
        mvc.perform(get(url).with(aPersonWith(AccessRole.ADMINISTRATOR, true))).andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.startsWith("attachment"), org.hamcrest.Matchers.containsString("evidence-pack-"), org.hamcrest.Matchers.containsString(".zip"))));
        for (AccessRole denied : List.of(AccessRole.REVIEWER, AccessRole.BUSINESS_ANALYST, AccessRole.VIEWER, AccessRole.TESTER, AccessRole.DEVELOPER)) {
            mvc.perform(get(url).with(aPersonWith(denied, false))).andExpect(status().isForbidden());
        }
        mvc.perform(get(url).with(aPersonWith(null, false))).andExpect(status().isForbidden());
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/baselines/" + UUID.randomUUID() + "/evidence-pack").with(aPersonWith(AccessRole.APPROVER, false))).andExpect(status().isNotFound());
        // refused requests leave nothing on the audit log; the four allowed ones left four
        assertThat(auditCount(base.getId(), "baseline.evidence_pack_exported")).isEqualTo(4);
    }
}
