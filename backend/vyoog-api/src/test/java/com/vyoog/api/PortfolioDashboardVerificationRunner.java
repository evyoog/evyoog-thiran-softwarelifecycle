package com.vyoog.api;

import com.vyoog.api.web.ApplicationController;
import com.vyoog.api.web.CapabilityController;
import com.vyoog.api.web.ProductController;
import com.vyoog.portfolio.ProductDashboardService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VYB-0788: the Portfolio dashboard redesign — real per-product/per-application
 * counts, a real V011 migration (vertical/purpose renames, owner/lifecycle/mark
 * columns), and the CSV report, all proven against the live database rather than
 * trusting a clean compile. Same explicit-name-only convention as
 * Session14VerificationRunner/Session16VerificationRunner — invisible to
 * {@code mvn test}/{@code mvn verify}.
 *
 * <pre>
 * DB_URL=... DB_USER=... DB_PASSWORD=... mvn -pl vyoog-api -am test \
 *   -Dtest=PortfolioDashboardVerificationRunner -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@SpringBootTest
class PortfolioDashboardVerificationRunner {

    @Autowired JdbcTemplate jdbc;
    @Autowired ProductController productController;
    @Autowired ApplicationController applicationController;
    @Autowired CapabilityController capabilityController;
    @Autowired ProductDashboardService dashboardService;

    @Test
    void dashboardComputesRealCountsAndTheReportMatches() {
        UUID ownerId = jdbc.queryForObject("""
            INSERT INTO app_user (subject, email, display_name)
            VALUES ('s17-owner-sub', 'owner@s17.test', 'S17 Owner') RETURNING id
            """, UUID.class);

        // Real end-to-end product creation through the actual controller — not a
        // direct repository save — so this also proves the vertical/purpose/owner/
        // lifecycle/mark fields round-trip through the real HTTP-facing DTOs.
        var created = productController.create(new ProductController.CreateProduct(
            "S17DASH", "Session 17 Dashboard Product", "Test Intelligence",
            "Prove the dashboard's counts are real.", ownerId.toString(), "LIVE", "database"));
        assertThat(created.vertical()).isEqualTo("Test Intelligence");
        assertThat(created.ownerId()).isEqualTo(ownerId.toString());
        assertThat(created.lifecycleStatus()).isEqualTo("LIVE");
        assertThat(created.mark()).isEqualTo("database");
        UUID productId = UUID.fromString(created.id());

        UUID appId = jdbc.queryForObject(
            "INSERT INTO application (product_id, name) VALUES (?, 'S17 App') RETURNING id", UUID.class, productId);
        UUID capId = jdbc.queryForObject(
            "INSERT INTO capability (application_id, name) VALUES (?, 'S17 Cap') RETURNING id", UUID.class, appId);
        // VYB-0810: VERIFIED is not a requirement status any more — APPROVED is the
        // pipeline's terminal one, and it is what ProductDashboardService's
        // verifiedCount/verifiedRatio now counts (the field names stayed; the SQL
        // predicate behind them moved to APPROVED).
        UUID verifiedReqId = jdbc.queryForObject("""
            INSERT INTO requirement (key, capability_id, type, status, title, statement)
            VALUES ('VY-S17V', ?, 'FUNCTIONAL', 'APPROVED', 'verified req', 'statement')
            RETURNING id""", UUID.class, capId);
        UUID draftReqId = jdbc.queryForObject("""
            INSERT INTO requirement (key, capability_id, type, status, title, statement)
            VALUES ('VY-S17D', ?, 'FUNCTIONAL', 'DRAFT', 'draft req with a gap', 'statement')
            RETURNING id""", UUID.class, capId);
        jdbc.update("""
            INSERT INTO finding (rule_key, fingerprint, object_type, object_id, severity, title, state)
            VALUES ('noverify', 's17-gap-fingerprint', 'REQUIREMENT', ?, 'high', 'no test linked', 'OPEN')
            """, draftReqId);

        try {
            var dashboard = dashboardService.dashboard();
            var product = dashboard.products().stream()
                .filter(p -> p.id().equals(productId.toString())).findFirst().orElseThrow();
            System.out.println("[verify] product summary: reqCount=" + product.reqCount()
                + " appCount=" + product.appCount() + " gapCount=" + product.gapCount()
                + " verifiedRatio=" + product.verifiedRatio() + " ownerName=" + product.ownerName());

            // Two requirements under this product's one application: one VERIFIED,
            // one DRAFT with one OPEN finding — real, computed, not asserted blind.
            assertThat(product.reqCount()).isEqualTo(2);
            assertThat(product.appCount()).isEqualTo(1);
            assertThat(product.gapCount()).isEqualTo(1);
            assertThat(product.verifiedRatio()).isEqualTo(0.5);
            assertThat(product.ownerName()).isEqualTo("S17 Owner");
            assertThat(product.apps()).hasSize(1);
            assertThat(product.apps().get(0).reqCount()).isEqualTo(2);
            assertThat(product.apps().get(0).gapCount()).isEqualTo(1);

            String csv = dashboardService.dashboardCsv();
            System.out.println("[verify] csv contains product row: " + csv.lines().anyMatch(l -> l.contains("S17DASH")));
            assertThat(csv).contains("S17DASH").contains("S17 App").contains("S17 Owner");
            // The CSV's own req/gap figures for this app must match the dashboard's,
            // not just that the product's name appears somewhere in the file.
            assertThat(csv.lines().filter(l -> l.contains("S17 App")).findFirst().orElseThrow())
                .contains(",2,1,");
        } finally {
            jdbc.update("DELETE FROM finding WHERE object_id IN (?, ?)", draftReqId, verifiedReqId);
            jdbc.update("DELETE FROM requirement WHERE id IN (?, ?)", verifiedReqId, draftReqId);
            jdbc.update("DELETE FROM capability WHERE id = ?", capId);
            jdbc.update("DELETE FROM application WHERE id = ?", appId);
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
            jdbc.update("DELETE FROM app_user WHERE id = ?", ownerId);
        }
    }

    /**
     * VYB-0832: application already had a description column end to end; only capability
     * needed a new one (this session's V032 migration). Both proven together, through
     * the real controllers, against the real live database.
     */
    @Test
    void applicationAndCapabilityDescriptionsRoundTripThroughRealControllers() {
        UUID productId = jdbc.queryForObject(
            "INSERT INTO product (key, name) VALUES ('VYB0832', 'VYB-0832 Product') RETURNING id", UUID.class);

        var app = applicationController.create(productId,
            new ApplicationController.CreateApplication("VYB-0832 App", "What this app covers."));
        assertThat(app.description()).isEqualTo("What this app covers.");
        UUID appId = UUID.fromString(app.id());

        var updatedApp = applicationController.update(productId, appId,
            new ApplicationController.UpdateApplication("VYB-0832 App", "Updated app description."));
        assertThat(updatedApp.description()).isEqualTo("Updated app description.");

        var cap = capabilityController.create(appId,
            new CapabilityController.CreateCapability("VYB-0832 Cap", "CAP", "A capability description."));
        assertThat(cap.code()).isEqualTo("CAP");
        assertThat(cap.description()).isEqualTo("A capability description.");
        UUID capId = UUID.fromString(cap.id());

        var updatedCap = capabilityController.update(appId, capId,
            new CapabilityController.UpdateCapability("VYB-0832 Cap", "CAP", "Updated capability description."));
        assertThat(updatedCap.description()).isEqualTo("Updated capability description.");

        try {
            String reloaded = jdbc.queryForObject("SELECT description FROM capability WHERE id = ?", String.class, capId);
            System.out.println("[verify] capability.description in Postgres -> " + reloaded);
            assertThat(reloaded).isEqualTo("Updated capability description.");
        } finally {
            jdbc.update("DELETE FROM capability WHERE id = ?", capId);
            jdbc.update("DELETE FROM application WHERE id = ?", appId);
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
        }
    }
}
