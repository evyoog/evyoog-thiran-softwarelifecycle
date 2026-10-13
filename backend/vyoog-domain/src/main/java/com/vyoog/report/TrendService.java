package com.vyoog.report;

import com.vyoog.release.ReadinessSnapshotService;
import com.vyoog.release.ReadinessSnapshotService.Snapshot;
import com.vyoog.release.ReleaseService;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0956 (F24, D43): trends over time for defects, the test pass rate and release readiness, read-only and derived from what is
 * already recorded. Weeks run Monday to Sunday in UTC. Counts and rates only: nothing is per person (rule 7).
 *
 * <ul>
 *   <li><b>Defects</b>, per week: raised, closed, and the backlog at the end of the week, which is every defect not closed then
 *       (open or fixed). The state at a moment is read from the recorded transitions, so a reopened defect is back in the backlog.</li>
 *   <li><b>Test pass rate</b>, per week: of the pass and fail results recorded that week (CI and manual together), the share that
 *       passed. A week with no results has no rate: it is "no results", not zero.</li>
 *   <li><b>Release readiness</b>, per recorded day of one release: the snapshots {@link ReadinessSnapshotService} has kept, and the
 *       figures as they are now.</li>
 * </ul>
 *
 * <p>The product and application filters narrow defects and results to requirements placed anywhere in it (the requirement
 * scope, at any placement level); a defect with no requirement is counted only while no filter is set. A release spans its own
 * scope, so the filters do not apply to readiness.
 */
@Service
public class TrendService {

    public static final Set<Integer> PERIODS = Set.of(30, 90, 180);

    public record DefectWeek(LocalDate weekStart, int opened, int closed, int open) {}
    public record PassWeek(LocalDate weekStart, int passed, int failed, Double rate) {}
    public record ReadinessTrend(UUID releaseId, String releaseName, String state, List<Snapshot> history, Snapshot current) {}
    public record Trends(Instant generatedAt, int days, UUID productId, String productName, UUID applicationId, String applicationName,
                         List<DefectWeek> defects, List<PassWeek> passRate, ReadinessTrend readiness) {}

    private final JdbcTemplate jdbc;
    private final ReadinessSnapshotService snapshots;
    private final ReleaseService releases;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public TrendService(JdbcTemplate jdbc, ReadinessSnapshotService snapshots, ReleaseService releases) {
        this(jdbc, snapshots, releases, Clock.systemUTC());
    }

    TrendService(JdbcTemplate jdbc, ReadinessSnapshotService snapshots, ReleaseService releases, Clock clock) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
        this.releases = releases;
        this.clock = clock;
    }

    /** The Monday of each week of the period, oldest first, ending with the week that holds today. */
    static List<LocalDate> weeks(LocalDate today, int days) {
        LocalDate thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        int count = (days + 6) / 7;
        List<LocalDate> out = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) out.add(thisWeek.minusWeeks(i));
        return out;
    }

    private record ScopeSql(String join, String where, List<Object> args) {}

    private static ScopeSql scopeSql(String requirementIdColumn, UUID productId, UUID applicationId) {
        if (productId == null && applicationId == null) return new ScopeSql("", "", List.of());
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder();
        if (productId != null) { where.append(" AND rs.product_id = ?"); args.add(productId); }
        if (applicationId != null) { where.append(" AND rs.application_id = ?"); args.add(applicationId); }
        return new ScopeSql(" JOIN requirement_scope rs ON rs.requirement_id = " + requirementIdColumn, where.toString(), args);
    }

    private String nameOf(String table, UUID id) {
        if (id == null) return null;
        var found = jdbc.queryForList("SELECT name FROM " + table + " WHERE id = ?", String.class, id);
        if (found.isEmpty()) throw new NoSuchElementException("No such " + table);
        return found.get(0);
    }

    public Trends trends(UUID productId, UUID applicationId, int days, UUID releaseId) {
        if (!PERIODS.contains(days)) throw new IllegalArgumentException("The period is 30, 90 or 180 days");
        String productName = nameOf("product", productId);
        String applicationName = nameOf("application", applicationId);
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        List<LocalDate> weeks = weeks(today, days);
        ReadinessTrend readiness = null;
        if (releaseId != null) {
            var release = releases.find(releaseId);
            readiness = new ReadinessTrend(releaseId, release.getName(), release.getState().name(),
                snapshots.history(releaseId, today.minusDays(days)), snapshots.measure(releaseId, today));
        }
        return new Trends(Instant.now(clock), days, productId, productName, applicationId, applicationName,
            defects(weeks, productId, applicationId), passRate(weeks, productId, applicationId), readiness);
    }

    private static String weekStartSql(String column) { return "((" + column + ")::timestamp AT TIME ZONE 'UTC')"; }

    List<DefectWeek> defects(List<LocalDate> weeks, UUID productId, UUID applicationId) {
        ScopeSql scope = scopeSql("d.requirement_id", productId, applicationId);
        // The defects in scope, once; the unscoped case keeps the ones with no requirement too.
        String defects = "SELECT d.id, d.raised_at FROM defect d" + scope.join() + " WHERE true" + scope.where();
        String sql = """
            WITH w AS (SELECT unnest(?::date[]) AS wk), d AS (%s)
            SELECT w.wk,
              (SELECT count(*) FROM d WHERE d.raised_at >= %s AND d.raised_at < %s) AS opened,
              (SELECT count(*) FROM defect_transition t JOIN d ON d.id = t.defect_id
                WHERE t.to_state = 'CLOSED' AND t.changed_at >= %s AND t.changed_at < %s) AS closed,
              (SELECT count(*) FROM d WHERE d.raised_at < %s
                 AND coalesce((SELECT t.to_state FROM defect_transition t WHERE t.defect_id = d.id AND t.changed_at < %s
                                ORDER BY t.changed_at DESC, t.id DESC LIMIT 1), 'OPEN') <> 'CLOSED') AS open_at_end
            FROM w ORDER BY w.wk
            """.formatted(defects, weekStartSql("w.wk"), weekStartSql("w.wk + 7"), weekStartSql("w.wk"), weekStartSql("w.wk + 7"),
            weekStartSql("w.wk + 7"), weekStartSql("w.wk + 7"));
        List<Object> args = new ArrayList<>();
        args.add(dates(weeks));
        args.addAll(scope.args());
        return jdbc.query(sql, (rs, n) -> new DefectWeek(rs.getDate("wk").toLocalDate(), rs.getInt("opened"), rs.getInt("closed"), rs.getInt("open_at_end")),
            args.toArray());
    }

    List<PassWeek> passRate(List<LocalDate> weeks, UUID productId, UUID applicationId) {
        ScopeSql scope = scopeSql("v.requirement_id", productId, applicationId);
        String sql = """
            WITH w AS (SELECT unnest(?::date[]) AS wk)
            SELECT w.wk,
              (SELECT count(*) FROM verification v%1$s WHERE v.result = 'PASS' AND v.verified_at >= %2$s AND v.verified_at < %3$s%4$s) AS passed,
              (SELECT count(*) FROM verification v%1$s WHERE v.result = 'FAIL' AND v.verified_at >= %2$s AND v.verified_at < %3$s%4$s) AS failed
            FROM w ORDER BY w.wk
            """.formatted(scope.join(), weekStartSql("w.wk"), weekStartSql("w.wk + 7"), scope.where());
        List<Object> args = new ArrayList<>();
        args.add(dates(weeks));
        args.addAll(scope.args());
        args.addAll(scope.args());
        return jdbc.query(sql, (rs, n) -> {
            int passed = rs.getInt("passed"), failed = rs.getInt("failed");
            return new PassWeek(rs.getDate("wk").toLocalDate(), passed, failed, passed + failed == 0 ? null : (double) passed / (passed + failed));
        }, args.toArray());
    }

    /** A Postgres array literal of the week starts, bound with {@code ?::date[]}. */
    private static String dates(List<LocalDate> weeks) {
        return "{" + String.join(",", weeks.stream().map(LocalDate::toString).toList()) + "}";
    }
}
