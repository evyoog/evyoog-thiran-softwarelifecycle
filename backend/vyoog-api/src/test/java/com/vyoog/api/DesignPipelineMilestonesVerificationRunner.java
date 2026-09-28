package com.vyoog.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vyoog.api.web.DesignController;
import com.vyoog.design.DesignService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * VYB-0816 against the real database — proven the same way Session14/16/17's
 * *VerificationRunner classes are (see PortfolioDashboardVerificationRunner): this
 * environment has no Docker, so the PostgresFixture/Testcontainers route used by
 * RequirementReviewedStatusIT cannot run here. Explicit-name-only convention,
 * invisible to {@code mvn test}/{@code mvn verify}:
 *
 * <pre>
 * mvn -pl vyoog-api -am test -Dtest=DesignPipelineMilestonesVerificationRunner \
 *   -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@SpringBootTest
class DesignPipelineMilestonesVerificationRunner {

    @Autowired JdbcTemplate jdbc;
    @Autowired DesignService designService;
    @Autowired DesignController designController;

    @Test
    void generateAddsTestingAndDeploymentMilestonesWiredToRealEvidence() {
        UUID productId = jdbc.queryForObject(
            "INSERT INTO product (key, name) VALUES ('VYB0816', 'VYB-0816 Product') RETURNING id", UUID.class);
        UUID appId = jdbc.queryForObject(
            "INSERT INTO application (product_id, name) VALUES (?, 'VYB-0816 App') RETURNING id", UUID.class, productId);
        UUID capId = jdbc.queryForObject(
            "INSERT INTO capability (application_id, name) VALUES (?, 'VYB-0816 Cap') RETURNING id", UUID.class, appId);

        // Two requirements: one with real passing-test and real deployment evidence,
        // one with neither — proves the milestones read actual evidence per requirement,
        // not just "did generate run."
        UUID doneReqId = jdbc.queryForObject("""
            INSERT INTO requirement (key, capability_id, type, status, title, statement, revision)
            VALUES ('VY-0816A', ?, 'FUNCTIONAL', 'APPROVED', 'Done requirement', 'statement', 1)
            RETURNING id""", UUID.class, capId);
        UUID pendingReqId = jdbc.queryForObject("""
            INSERT INTO requirement (key, capability_id, type, status, title, statement, revision)
            VALUES ('VY-0816B', ?, 'FUNCTIONAL', 'APPROVED', 'Pending requirement', 'statement', 1)
            RETURNING id""", UUID.class, capId);

        jdbc.update("""
            INSERT INTO verification (requirement_id, requirement_revision, result)
            VALUES (?, 1, 'PASS')
            """, doneReqId);

        UUID envId = jdbc.queryForObject(
            "INSERT INTO environment (name) VALUES ('VYB0816-env') RETURNING id", UUID.class);
        UUID deploymentId = jdbc.queryForObject(
            "INSERT INTO deployment (environment_id, build_label) VALUES (?, 'build-1') RETURNING id",
            UUID.class, envId);
        jdbc.update("""
            INSERT INTO deployment_requirement (deployment_id, requirement_id, revision) VALUES (?, ?, 1)
            """, deploymentId, doneReqId);

        try {
            var flow = designService.createFlow(appId, UUID.randomUUID());
            UUID flowId = flow.getId();

            var generated = designService.generateFrom(flowId, appId, UUID.randomUUID());
            assertThat(generated.nodesCreated()).isEqualTo(4); // 2 requirement nodes + Testing + Deployment

            var nodes = designController.nodes(flowId);
            var testingNode = nodes.stream().filter(n -> n.kind().equals("testing")).findFirst().orElseThrow();
            var deploymentNode = nodes.stream().filter(n -> n.kind().equals("deployment")).findFirst().orElseThrow();
            assertThat(testingNode.requirementIds()).containsExactlyInAnyOrder(
                doneReqId.toString(), pendingReqId.toString());
            assertThat(deploymentNode.requirementIds()).containsExactlyInAnyOrder(
                doneReqId.toString(), pendingReqId.toString());

            var edges = designController.edges(flowId);
            var doneReqNode = nodes.stream()
                .filter(n -> n.requirementIds().contains(doneReqId.toString())).findFirst().orElseThrow();
            var pendingReqNode = nodes.stream()
                .filter(n -> n.requirementIds().contains(pendingReqId.toString())).findFirst().orElseThrow();
            assertThat(edges).anyMatch(e -> e.fromNode().equals(doneReqNode.id()) && e.toNode().equals(testingNode.id()));
            assertThat(edges).anyMatch(e -> e.fromNode().equals(pendingReqNode.id()) && e.toNode().equals(testingNode.id()));
            assertThat(edges).anyMatch(e -> e.fromNode().equals(testingNode.id()) && e.toNode().equals(deploymentNode.id()));

            // The real point of VYB-0816: progress is real evidence, not requirement.status
            // (both requirements are APPROVED, but only one actually has a passing test /
            // an actual deployment record).
            var progress = designController.progress(flowId);
            assertThat(progress.totalRequirements()).isEqualTo(2);
            assertThat(progress.verifiedRequirements()).isEqualTo(1);
            assertThat(progress.verifiedPct()).isEqualTo(50);
            assertThat(progress.deployedRequirements()).isEqualTo(1);
            assertThat(progress.deployedPct()).isEqualTo(50);

            // Re-running generate is additive/idempotent: no duplicate milestone nodes or edges.
            var regenerated = designService.generateFrom(flowId, appId, UUID.randomUUID());
            assertThat(regenerated.nodesCreated()).isEqualTo(0);
            assertThat(designController.nodes(flowId)).hasSize(4);
            assertThat(designController.edges(flowId)).hasSize(edges.size());
        } finally {
            // deployment_requirement.requirement_id and deployment_requirement.deployment_id
            // both lack ON DELETE CASCADE from this side, so they must go first; everything
            // else (verification, design flow/node/edge, requirement itself) cascades from
            // product once these are clear.
            jdbc.update("DELETE FROM deployment_requirement WHERE deployment_id = ?", deploymentId);
            jdbc.update("DELETE FROM deployment WHERE id = ?", deploymentId);
            jdbc.update("DELETE FROM environment WHERE id = ?", envId);
            jdbc.update("DELETE FROM requirement WHERE capability_id = ?", capId);
            jdbc.update("DELETE FROM product WHERE id = ?", productId); // cascades application/capability/flow/nodes/edges
        }
    }
}
