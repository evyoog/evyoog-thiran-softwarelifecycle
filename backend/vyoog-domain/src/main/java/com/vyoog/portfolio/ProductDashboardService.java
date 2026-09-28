package com.vyoog.portfolio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0788: the Portfolio dashboard's per-product and per-application figures — real
 * counts, two real aggregate queries (not N+1 per product), the same "gap = an OPEN
 * finding on that requirement" definition {@link com.vyoog.signals.SignalsService}
 * already established, reused rather than invented a second time. Archived products
 * and applications are excluded, same as every other portfolio listing.
 */
@Service
public class ProductDashboardService {

    private final JdbcTemplate jdbc;

    public ProductDashboardService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AppSummary(String id, String name, long reqCount, long gapCount, long verifiedCount) {
        public double verifiedRatio() { return reqCount == 0 ? 0.0 : (double) verifiedCount / reqCount; }
    }

    public record ProductSummary(
        String id, String key, String name, String vertical, String purpose,
        String ownerId, String ownerName, String lifecycleStatus, String mark,
        long reqCount, long appCount, long gapCount, double verifiedRatio,
        List<AppSummary> apps
    ) {}

    public record Totals(int productCount, long appCount, long reqCount, long gapCount, long appsBelowThreshold) {}

    /**
     * One capability's rollup, for the app detail screen's capability cards.
     *
     * <p>Same shape and same definitions as {@link AppSummary} — "gap" is an OPEN finding
     * against one of its requirements. VYB-0810: "verified" here (the field name is kept
     * to avoid a mechanical rename across the API and every screen that reads it) counts
     * APPROVED requirements, not a VERIFIED status — that status no longer exists, and
     * APPROVED is now the terminal stage of the lifecycle. Every screen showing this number
     * labels it "Approved", not "Verified" — because the card grid sits directly under the
     * app header showing the app's own totals, and two different definitions on one screen
     * is how a number nobody can reconcile appears.
     */
    public record CapabilitySummary(String id, String name, String code,
                                     long reqCount, long gapCount, long verifiedCount) {
        public double verifiedRatio() { return reqCount == 0 ? 0.0 : (double) verifiedCount / reqCount; }
    }

    /** The capabilities of one application, with their counts. Archived ones are excluded. */
    public List<CapabilitySummary> capabilitiesOf(UUID applicationId) {
        return jdbc.query("""
            SELECT c.id, c.name, c.code,
                   COUNT(DISTINCT r.id) AS req_count,
                   COUNT(DISTINCT f.id) FILTER (WHERE f.state = 'OPEN') AS gap_count,
                   COUNT(DISTINCT r.id) FILTER (WHERE r.status = 'APPROVED') AS verified_count
            FROM capability c
            LEFT JOIN requirement r ON r.capability_id = c.id AND r.deleted_at IS NULL
            LEFT JOIN finding f ON f.object_id = r.id AND f.object_type = 'REQUIREMENT'
            WHERE c.application_id = ? AND c.archived_at IS NULL
            GROUP BY c.id, c.name, c.code
            ORDER BY c.name""",
            (rs, i) -> new CapabilitySummary(rs.getString("id"), rs.getString("name"), rs.getString("code"),
                rs.getLong("req_count"), rs.getLong("gap_count"), rs.getLong("verified_count")),
            applicationId);
    }

    public record Dashboard(Totals totals, List<ProductSummary> products) {}

    private static final double COVERAGE_THRESHOLD = 0.75;

    public Dashboard dashboard() {
        record ProductRow(UUID id, String key, String name, String vertical, String purpose,
                           UUID ownerId, String ownerName, String lifecycleStatus, String mark) {}

        List<ProductRow> productRows = jdbc.query("""
            SELECT p.id, p.key, p.name, p.vertical, p.purpose, p.owner_id,
                   u.display_name AS owner_name, p.lifecycle_status, p.mark
            FROM product p
            LEFT JOIN app_user u ON u.id = p.owner_id
            WHERE p.archived_at IS NULL
            ORDER BY p.name""",
            (rs, i) -> new ProductRow(
                UUID.fromString(rs.getString("id")), rs.getString("key"), rs.getString("name"),
                rs.getString("vertical"), rs.getString("purpose"),
                rs.getString("owner_id") == null ? null : UUID.fromString(rs.getString("owner_id")),
                rs.getString("owner_name"), rs.getString("lifecycle_status"), rs.getString("mark")));

        // One aggregate query across every non-archived application, grouped down to
        // requirement/finding level — a product or application with zero requirements
        // simply produces a zero-req row rather than a fabricated ratio.
        record AppRow(UUID productId, AppSummary app) {}
        List<AppRow> appRowsWithProduct = jdbc.query("""
            SELECT a.product_id, a.id AS app_id, a.name AS app_name,
                   COUNT(DISTINCT r.id) AS req_count,
                   COUNT(DISTINCT f.id) FILTER (WHERE f.state = 'OPEN') AS gap_count,
                   COUNT(DISTINCT r.id) FILTER (WHERE r.status = 'APPROVED') AS verified_count
            FROM application a
            LEFT JOIN capability c ON c.application_id = a.id AND c.archived_at IS NULL
            LEFT JOIN requirement r ON r.capability_id = c.id AND r.deleted_at IS NULL
            LEFT JOIN finding f ON f.object_id = r.id AND f.object_type = 'REQUIREMENT'
            WHERE a.archived_at IS NULL
            GROUP BY a.product_id, a.id, a.name
            ORDER BY a.name""",
            (rs, i) -> new AppRow(UUID.fromString(rs.getString("product_id")),
                new AppSummary(rs.getString("app_id"), rs.getString("app_name"),
                    rs.getLong("req_count"), rs.getLong("gap_count"), rs.getLong("verified_count"))));

        Map<UUID, List<AppSummary>> appsByProduct = new LinkedHashMap<>();
        for (AppRow row : appRowsWithProduct) {
            appsByProduct.computeIfAbsent(row.productId(), k -> new ArrayList<>()).add(row.app());
        }

        List<ProductSummary> products = new ArrayList<>();
        long totalApps = 0, totalReqs = 0, totalGaps = 0, appsBelowThreshold = 0;
        for (ProductRow p : productRows) {
            List<AppSummary> apps = appsByProduct.getOrDefault(p.id(), List.of());
            long reqCount = apps.stream().mapToLong(AppSummary::reqCount).sum();
            long gapCount = apps.stream().mapToLong(AppSummary::gapCount).sum();
            long verifiedCount = apps.stream().mapToLong(AppSummary::verifiedCount).sum();
            double ratio = reqCount == 0 ? 0.0 : (double) verifiedCount / reqCount;

            products.add(new ProductSummary(
                p.id().toString(), p.key(), p.name(), p.vertical(), p.purpose(),
                p.ownerId() == null ? null : p.ownerId().toString(), p.ownerName(),
                p.lifecycleStatus(), p.mark(), reqCount, apps.size(), gapCount, ratio, apps));

            totalApps += apps.size();
            totalReqs += reqCount;
            totalGaps += gapCount;
            // VYB-0788: only an application with at least one requirement has a
            // meaningful coverage ratio — an empty application isn't "below 75%",
            // it has nothing to be below yet.
            appsBelowThreshold += apps.stream()
                .filter(a -> a.reqCount() > 0 && a.verifiedRatio() < COVERAGE_THRESHOLD).count();
        }

        return new Dashboard(
            new Totals(products.size(), totalApps, totalReqs, totalGaps, appsBelowThreshold),
            products);
    }

    /** VYB-0788: the "Portfolio report" download — the same {@link #dashboard()} figures, one row per application. */
    public String dashboardCsv() {
        Dashboard d = dashboard();
        StringBuilder csv = new StringBuilder(
            "product_key,product_name,vertical,owner,lifecycle_status,application,req_count,gap_count,verified_ratio\n");
        for (ProductSummary p : d.products()) {
            if (p.apps().isEmpty()) {
                csv.append(csvRow(p, null));
            } else {
                for (AppSummary a : p.apps()) csv.append(csvRow(p, a));
            }
        }
        return csv.toString();
    }

    private static String csvRow(ProductSummary p, AppSummary a) {
        return String.join(",",
            csvField(p.key()), csvField(p.name()), csvField(p.vertical()), csvField(p.ownerName()),
            csvField(p.lifecycleStatus()), csvField(a == null ? "" : a.name()),
            String.valueOf(a == null ? p.reqCount() : a.reqCount()),
            String.valueOf(a == null ? p.gapCount() : a.gapCount()),
            a == null ? String.valueOf(p.verifiedRatio()) : String.valueOf(a.verifiedRatio())
        ) + "\n";
    }

    private static String csvField(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
