package com.vyoog.api;

import com.vyoog.evidence.TestCaseQueryService;
import com.vyoog.evidence.TestCaseService;
import com.vyoog.evidence.TestCaseSuggestionService;
import com.vyoog.requirements.RequirementRepository;
import com.vyoog.requirements.RequirementSpecifications;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VYB-0825: {@link TestCaseQueryService}'s lateral-join SQL proven against the real,
 * live database this sandbox actually has (no Docker here, so the Testcontainers-backed
 * {@code TestCaseServiceIT} can't run — this exercises the same query against the real
 * schema instead). Same explicit-name-only convention as every other
 * {@code *VerificationRunner} — invisible to {@code mvn test}/{@code mvn verify} — and
 * the same reason this one isn't named starting with the word "Test": Surefire's
 * default include pattern picks up any file whose name starts with that word, and
 * would run it anyway (the mistake caught and fixed in VYB-0824).
 *
 * <pre>
 * DB_URL=... DB_USER=... DB_PASSWORD=... mvn -pl vyoog-api -am test \
 *   -Dtest=ListTestCasesVerificationRunner -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * Cleans up everything it creates.
 */
@SpringBootTest
class ListTestCasesVerificationRunner {

    @Autowired JdbcTemplate jdbc;
    @Autowired TestCaseService testCaseService;
    @Autowired TestCaseQueryService queryService;
    @Autowired TestCaseSuggestionService suggestionService;
    @Autowired RequirementRepository requirements;

    @Test
    void listsByOwnTextAndByVerifiedRequirementAgainstRealPostgres() {
        UUID productId = jdbc.queryForObject(
            "INSERT INTO product (key, name) VALUES ('VYB0825-VERIFY', 'VYB-0825 Verify') RETURNING id", UUID.class);
        UUID appId = jdbc.queryForObject(
            "INSERT INTO application (product_id, name) VALUES (?, 'App') RETURNING id", UUID.class, productId);
        UUID capId = jdbc.queryForObject(
            "INSERT INTO capability (application_id, name) VALUES (?, 'Cap') RETURNING id", UUID.class, appId);
        UUID reqId = jdbc.queryForObject("""
            INSERT INTO requirement (key, capability_id, type, title, statement)
            VALUES ('VY-0825VERIFY', ?, 'FUNCTIONAL', 'unique-req-title-0825', 'verify statement') RETURNING id
            """, UUID.class, capId);

        try {
            var tc = testCaseService.draft("unique-tc-title-0825", "steps", null, reqId, null);
            System.out.println("[verify] drafted " + tc.getKey());

            var byOwnTitle = queryService.list("unique-tc-title-0825", null, PageRequest.of(0, 10));
            System.out.println("[verify] search by own title -> " + byOwnTitle.getContent());
            assertThat(byOwnTitle.getContent()).extracting(TestCaseQueryService.Row::id).contains(tc.getId());

            var byRequirementTitle = queryService.list("unique-req-title-0825", null, PageRequest.of(0, 10));
            System.out.println("[verify] search by requirement title -> " + byRequirementTitle.getContent());
            assertThat(byRequirementTitle.getContent()).hasSize(1);
            assertThat(byRequirementTitle.getContent().get(0).requirementId()).isEqualTo(reqId);
            assertThat(byRequirementTitle.getContent().get(0).status()).isEqualTo("DRAFT");

            var wrongStatus = queryService.list("unique-tc-title-0825", "INGESTED", PageRequest.of(0, 10));
            assertThat(wrongStatus.getContent()).isEmpty();

            var noMatch = queryService.list("no-such-thing-anywhere-0825", null, PageRequest.of(0, 10));
            assertThat(noMatch.getContent()).isEmpty();

            verifyByRequirementAgainstRealPostgres(reqId);
        } finally {
            jdbc.update("DELETE FROM test_case WHERE id IN (SELECT from_id FROM trace_link WHERE to_id = ?)", reqId);
            jdbc.update("DELETE FROM finding WHERE object_id = ?", reqId);
            jdbc.update("DELETE FROM trace_closure WHERE ancestor_id = ? OR descendant_id = ?", reqId, reqId);
            jdbc.update("DELETE FROM trace_link WHERE to_id = ? OR from_id = ?", reqId, reqId);
            jdbc.update("DELETE FROM requirement WHERE id = ?", reqId);
            jdbc.update("DELETE FROM capability WHERE id = ?", capId);
            jdbc.update("DELETE FROM application WHERE id = ?", appId);
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
        }
    }

    /** VYB-0827: the by-requirement summary/detail queries, category counts included. */
    private void verifyByRequirementAgainstRealPostgres(UUID reqId) {
        var individual = testCaseService.draft("individual case 0827", "d", com.vyoog.evidence.TestCase.Category.INDIVIDUAL, reqId, null);
        var dependency = testCaseService.draft("dependency case 0827", "d", com.vyoog.evidence.TestCase.Category.DEPENDENCY, reqId, null);
        var uncategorized = testCaseService.draft("manual case 0827", "d", null, reqId, null);
        try {
            var summary = queryService.listRequirementsWithTestCases("unique-req-title-0825", PageRequest.of(0, 10));
            System.out.println("[verify] by-requirement summary -> " + summary.getContent());
            assertThat(summary.getContent()).hasSize(1);
            var row = summary.getContent().get(0);
            assertThat(row.requirementId()).isEqualTo(reqId);
            // +1 individual/+1 for the earlier "unique-tc-title-0825" draft, which has no category.
            assertThat(row.individualCount()).isEqualTo(1);
            assertThat(row.dependencyCount()).isEqualTo(1);
            assertThat(row.otherCount()).isEqualTo(2);
            assertThat(row.totalCount()).isEqualTo(4);

            var detail = queryService.listForRequirement(reqId);
            System.out.println("[verify] test cases for requirement -> " + detail);
            assertThat(detail).extracting(TestCaseQueryService.RequirementTestCaseRow::id)
                .containsExactlyInAnyOrder(individual.getId(), dependency.getId(), uncategorized.getId(),
                    detail.stream().filter(r -> r.title().equals("unique-tc-title-0825")).findFirst().orElseThrow().id());
            assertThat(detail).filteredOn(r -> r.id().equals(individual.getId()))
                .extracting(TestCaseQueryService.RequirementTestCaseRow::category).containsExactly("INDIVIDUAL");
            assertThat(detail).filteredOn(r -> r.id().equals(dependency.getId()))
                .extracting(TestCaseQueryService.RequirementTestCaseRow::category).containsExactly("DEPENDENCY");
            assertThat(detail).filteredOn(r -> r.id().equals(uncategorized.getId()))
                .extracting(TestCaseQueryService.RequirementTestCaseRow::category).containsExactly((String) null);

            // VYB-0828: editing an existing test case, against real Postgres.
            var edited = testCaseService.update(uncategorized.getId(), "edited title 0828", "edited desc",
                com.vyoog.evidence.TestCase.Category.INDIVIDUAL, null);
            System.out.println("[verify] edited -> title=" + edited.getTitle() + " category=" + edited.getCategory());
            assertThat(edited.getTitle()).isEqualTo("edited title 0828");
            assertThat(edited.getDescription()).isEqualTo("edited desc");
            assertThat(edited.getCategory()).isEqualTo(com.vyoog.evidence.TestCase.Category.INDIVIDUAL);
            assertThat(edited.getKey()).isEqualTo(uncategorized.getKey());
            var afterEdit = queryService.listForRequirement(reqId).stream()
                .filter(r -> r.id().equals(uncategorized.getId())).findFirst().orElseThrow();
            assertThat(afterEdit.title()).isEqualTo("edited title 0828");
            assertThat(afterEdit.category()).isEqualTo("INDIVIDUAL");
        } finally {
            jdbc.update("DELETE FROM test_case WHERE id IN (?, ?, ?)", individual.getId(), dependency.getId(), uncategorized.getId());
        }
    }

    /**
     * VYB-0830: the exact scenario the product owner gave — req-2 depends on req-1,
     * req-1 depends on req-0, and req-3/req-4 also depend on req-1 — proven against
     * real trace links, real Postgres, real generated link ids (the unit-test
     * equivalent can't exercise edge dedup honestly since directly-constructed
     * TraceLink objects have no id until persisted).
     */
    @Test
    void dependencyClusterWalksTheFullConnectedComponentAgainstRealPostgres() {
        UUID productId = jdbc.queryForObject(
            "INSERT INTO product (key, name) VALUES ('VYB0830-VERIFY', 'VYB-0830 Verify') RETURNING id", UUID.class);
        UUID appId = jdbc.queryForObject(
            "INSERT INTO application (product_id, name) VALUES (?, 'App') RETURNING id", UUID.class, productId);
        UUID capId = jdbc.queryForObject(
            "INSERT INTO capability (application_id, name) VALUES (?, 'Cap') RETURNING id", UUID.class, appId);

        UUID req0 = insertRequirement(capId, "VY-0830-0");
        UUID req1 = insertRequirement(capId, "VY-0830-1");
        UUID req2 = insertRequirement(capId, "VY-0830-2");
        UUID req3 = insertRequirement(capId, "VY-0830-3");
        UUID req4 = insertRequirement(capId, "VY-0830-4");

        insertLink(req2, req1); // req-2 depends on req-1
        insertLink(req1, req0); // req-1 depends on req-0
        insertLink(req3, req1); // req-3 depends on req-1
        insertLink(req4, req1); // req-4 depends on req-1

        try {
            var cluster = suggestionService.dependencyCluster(List.of(req2));
            System.out.println("[verify] cluster from req-2 alone -> " + cluster);

            assertThat(cluster.capped()).isFalse();
            assertThat(cluster.members()).extracting(TestCaseSuggestionService.ClusterMember::requirementId)
                .containsExactlyInAnyOrder(req0, req1, req2, req3, req4);
            assertThat(cluster.members()).filteredOn(m -> m.requirementId().equals(req2))
                .extracting(TestCaseSuggestionService.ClusterMember::selected).containsExactly(true);
            assertThat(cluster.members()).filteredOn(m -> !m.requirementId().equals(req2))
                .extracting(TestCaseSuggestionService.ClusterMember::selected).containsOnly(false);
            assertThat(cluster.edges()).hasSize(4);
            assertThat(cluster.edges()).extracting(TestCaseSuggestionService.ClusterEdge::fromKey)
                .containsExactlyInAnyOrder("VY-0830-2", "VY-0830-1", "VY-0830-3", "VY-0830-4");
        } finally {
            for (UUID id : List.of(req0, req1, req2, req3, req4)) {
                jdbc.update("DELETE FROM trace_closure WHERE ancestor_id = ? OR descendant_id = ?", id, id);
                jdbc.update("DELETE FROM trace_link WHERE to_id = ? OR from_id = ?", id, id);
            }
            for (UUID id : List.of(req0, req1, req2, req3, req4)) {
                jdbc.update("DELETE FROM finding WHERE object_id = ?", id);
                jdbc.update("DELETE FROM requirement WHERE id = ?", id);
            }
            jdbc.update("DELETE FROM capability WHERE id = ?", capId);
            jdbc.update("DELETE FROM application WHERE id = ?", appId);
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
        }
    }

    /**
     * VYB-0831: the bulk variant used by the delivery brief — one query for a whole
     * scope's test cases, grouped by requirement, against real Postgres. Also proves
     * {@code RequirementSpecifications.hasTestCase} against the same two requirements,
     * since it's the same VERIFIES link the bulk query itself reads.
     */
    @Test
    void listForRequirementsAndHasTestCaseAgainstRealPostgres() {
        UUID productId = jdbc.queryForObject(
            "INSERT INTO product (key, name) VALUES ('VYB0831-VERIFY', 'VYB-0831 Verify') RETURNING id", UUID.class);
        UUID appId = jdbc.queryForObject(
            "INSERT INTO application (product_id, name) VALUES (?, 'App') RETURNING id", UUID.class, productId);
        UUID capId = jdbc.queryForObject(
            "INSERT INTO capability (application_id, name) VALUES (?, 'Cap') RETURNING id", UUID.class, appId);

        UUID withTests = insertRequirement(capId, "VY-0831-A");
        UUID withoutTests = insertRequirement(capId, "VY-0831-B");

        var tc1 = testCaseService.draft("case one 0831", "d", com.vyoog.evidence.TestCase.Category.INDIVIDUAL, withTests, null);
        var tc2 = testCaseService.draft("case two 0831", "d", com.vyoog.evidence.TestCase.Category.DEPENDENCY, withTests, null);

        try {
            var byRequirement = queryService.listForRequirements(List.of(withTests, withoutTests));
            System.out.println("[verify] listForRequirements -> " + byRequirement);
            assertThat(byRequirement).containsKey(withTests).doesNotContainKey(withoutTests);
            assertThat(byRequirement.get(withTests)).extracting(TestCaseQueryService.RequirementTestCaseRow::id)
                .containsExactlyInAnyOrder(tc1.getId(), tc2.getId());
            assertThat(queryService.listForRequirements(List.of())).isEmpty();

            var ready = requirements.findAll(
                RequirementSpecifications.notDeleted().and(RequirementSpecifications.hasTestCase(true)), Pageable.unpaged());
            var notReady = requirements.findAll(
                RequirementSpecifications.notDeleted().and(RequirementSpecifications.hasTestCase(false)), Pageable.unpaged());
            System.out.println("[verify] hasTestCase(true) -> " + ready.map(r -> r.getKey()).toList());
            assertThat(ready).extracting(r -> r.getId()).contains(withTests).doesNotContain(withoutTests);
            assertThat(notReady).extracting(r -> r.getId()).contains(withoutTests).doesNotContain(withTests);
        } finally {
            jdbc.update("DELETE FROM test_case WHERE id IN (?, ?)", tc1.getId(), tc2.getId());
            jdbc.update("DELETE FROM trace_link WHERE to_id IN (?, ?)", withTests, withoutTests);
            jdbc.update("DELETE FROM requirement WHERE id IN (?, ?)", withTests, withoutTests);
            jdbc.update("DELETE FROM capability WHERE id = ?", capId);
            jdbc.update("DELETE FROM application WHERE id = ?", appId);
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
        }
    }

    private UUID insertRequirement(UUID capId, String key) {
        return jdbc.queryForObject("""
            INSERT INTO requirement (key, capability_id, type, title, statement)
            VALUES (?, ?, 'FUNCTIONAL', ?, 'verify statement') RETURNING id
            """, UUID.class, key, capId, key + " title");
    }

    private void insertLink(UUID fromRequirementId, UUID toRequirementId) {
        jdbc.update("""
            INSERT INTO trace_link (from_type, from_id, to_type, to_id, link_type)
            VALUES ('REQUIREMENT', ?, 'REQUIREMENT', ?, 'DERIVES')
            """, fromRequirementId, toRequirementId);
    }
}
