package com.vyoog.signals;

import com.vyoog.portfolio.Application;
import com.vyoog.portfolio.ApplicationRepository;
import com.vyoog.portfolio.Capability;
import com.vyoog.portfolio.CapabilityRepository;
import com.vyoog.trace.TraceGraphService;
import com.vyoog.trace.TraceObjectType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0460–0465. Every signal is one SQL query against real rows — no estimate, no
 * projection, nothing that isn't reproducible by running the same query again
 * (AC2/AC1). See {@link ScopeSignals} for why there is no ninth, combined figure.
 */
@Service
public class SignalsService {

    private final JdbcTemplate jdbc;
    private final ApplicationRepository applications;
    private final CapabilityRepository capabilities;
    private final TraceGraphService traceGraph;

    public SignalsService(JdbcTemplate jdbc, ApplicationRepository applications,
                           CapabilityRepository capabilities, TraceGraphService traceGraph) {
        this.jdbc = jdbc;
        this.applications = applications;
        this.capabilities = capabilities;
        this.traceGraph = traceGraph;
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private Long count(String sql, List<UUID> ids) {
        if (ids.isEmpty()) return 0L;
        return jdbc.queryForObject(sql, Long.class, ids.toArray());
    }

    /** VYB-0460/0461: the eight signals for one scope, each carrying the template that produced it. */
    public ScopeSignals compute(List<UUID> capabilityIds) {
        Map<String, String> q = new java.util.LinkedHashMap<>();

        String qCount = "SELECT count(*) FROM requirement WHERE deleted_at IS NULL AND capability_id IN (%s)";
        q.put("requirementCount", qCount);
        long requirementCount = count(qCount.formatted(placeholders(capabilityIds.size())), capabilityIds);

        String qCriteria = """
            SELECT count(*) FROM acceptance_criterion ac JOIN requirement r ON r.id = ac.requirement_id
            WHERE r.deleted_at IS NULL AND r.capability_id IN (%s)""";
        q.put("acceptanceCriteriaCount", qCriteria);
        long criteriaCount = count(qCriteria.formatted(placeholders(capabilityIds.size())), capabilityIds);

        String qDepth = """
            SELECT coalesce(max(tc.depth), 0) FROM trace_closure tc
            JOIN requirement r ON r.id = tc.ancestor_id AND tc.ancestor_type = 'REQUIREMENT'
            WHERE tc.descendant_type = 'REQUIREMENT' AND r.deleted_at IS NULL AND r.capability_id IN (%s)""";
        q.put("dependencyDepth", qDepth);
        long depth = count(qDepth.formatted(placeholders(capabilityIds.size())), capabilityIds);

        String qReach = """
            SELECT count(DISTINCT a.id) FROM trace_closure tc
            JOIN requirement origin ON origin.id = tc.ancestor_id AND tc.ancestor_type = 'REQUIREMENT'
            JOIN requirement other ON other.id = tc.descendant_id AND tc.descendant_type = 'REQUIREMENT'
            JOIN capability c ON c.id = other.capability_id
            JOIN application a ON a.id = c.application_id
            WHERE origin.deleted_at IS NULL AND origin.capability_id IN (%1$s)
              AND a.id NOT IN (
                SELECT DISTINCT a2.id FROM capability c2 JOIN application a2 ON a2.id = c2.application_id
                WHERE c2.id IN (%1$s))""";
        q.put("crossApplicationReach", qReach);
        long reach = capabilityIds.isEmpty() ? 0L : jdbc.queryForObject(
            qReach.formatted(placeholders(capabilityIds.size())), Long.class,
            concat(capabilityIds, capabilityIds));

        String qAmbig = """
            SELECT count(*) FROM finding f JOIN requirement r ON r.id = f.object_id AND f.object_type = 'REQUIREMENT'
            WHERE f.rule_key = 'ambig' AND f.state = 'OPEN' AND r.deleted_at IS NULL AND r.capability_id IN (%s)""";
        q.put("ambiguityLoad", qAmbig);
        long ambiguityLoad = count(qAmbig.formatted(placeholders(capabilityIds.size())), capabilityIds);

        String qGaps = """
            SELECT count(*) FROM finding f JOIN requirement r ON r.id = f.object_id AND f.object_type = 'REQUIREMENT'
            WHERE f.state = 'OPEN' AND r.deleted_at IS NULL AND r.capability_id IN (%s)""";
        q.put("openGaps", qGaps);
        long openGaps = count(qGaps.formatted(placeholders(capabilityIds.size())), capabilityIds);

        String qChanges = """
            SELECT count(*) FROM requirement_revision rr JOIN requirement r ON r.id = rr.requirement_id
            WHERE r.deleted_at IS NULL AND r.capability_id IN (%s) AND rr.changed_at > now() - interval '30 days'""";
        q.put("changeRate (per requirement, trailing 30 days)", qChanges);
        long changes = count(qChanges.formatted(placeholders(capabilityIds.size())), capabilityIds);

        String qNovel = """
            SELECT count(*) FROM requirement r
            WHERE r.deleted_at IS NULL AND r.capability_id IN (%s) AND r.created_at > now() - interval '30 days'""";
        q.put("novelty (proportion created in trailing 30 days)", qNovel);
        long novel = count(qNovel.formatted(placeholders(capabilityIds.size())), capabilityIds);

        double changeRate = requirementCount == 0 ? 0.0 : (double) changes / requirementCount;
        double novelty = requirementCount == 0 ? 0.0 : (double) novel / requirementCount;

        return new ScopeSignals(requirementCount, criteriaCount, depth, reach, ambiguityLoad, openGaps,
            changeRate, novelty, Instant.now(), q);
    }

    private static Object[] concat(List<UUID> a, List<UUID> b) {
        List<UUID> out = new ArrayList<>(a);
        out.addAll(b);
        return out.toArray();
    }

    /** VYB-0462 AC1: computed across every application in the portfolio, never hardcoded. */
    public ScopeSignals median() {
        List<ScopeSignals> perApplication = new ArrayList<>();
        for (Application app : allApplications()) {
            List<UUID> capIds = capabilities.findAllByApplicationIdAndArchivedAtIsNull(app.getId())
                .stream().map(Capability::getId).toList();
            if (capIds.isEmpty()) continue;
            perApplication.add(compute(capIds));
        }
        if (perApplication.isEmpty()) {
            return new ScopeSignals(0, 0, 0, 0, 0, 0, 0, 0, Instant.now(), Map.of());
        }
        return new ScopeSignals(
            medianLong(perApplication.stream().map(ScopeSignals::requirementCount).toList()),
            medianLong(perApplication.stream().map(ScopeSignals::acceptanceCriteriaCount).toList()),
            medianLong(perApplication.stream().map(ScopeSignals::dependencyDepth).toList()),
            medianLong(perApplication.stream().map(ScopeSignals::crossApplicationReach).toList()),
            medianLong(perApplication.stream().map(ScopeSignals::ambiguityLoad).toList()),
            medianLong(perApplication.stream().map(ScopeSignals::openGaps).toList()),
            medianDouble(perApplication.stream().map(ScopeSignals::changeRate).toList()),
            medianDouble(perApplication.stream().map(ScopeSignals::novelty).toList()),
            Instant.now(), Map.of());
    }

    private List<Application> allApplications() {
        return jdbc.query("SELECT id FROM application WHERE archived_at IS NULL",
            (rs, n) -> applications.findById(UUID.fromString(rs.getString("id"))).orElseThrow());
    }

    private static long medianLong(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }

    private static double medianDouble(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }

    /** VYB-0464: for one requirement, the exact volume affected — no figure in days (AC2). */
    public ImpactVolume computeImpact(UUID requirementId) {
        var descendants = traceGraph.downstream(TraceObjectType.REQUIREMENT, requirementId, TraceGraphService.MAX_DEPTH);
        var ascendants = traceGraph.upstream(TraceObjectType.REQUIREMENT, requirementId, TraceGraphService.MAX_DEPTH);

        java.util.Set<UUID> requirementIds = new java.util.HashSet<>();
        requirementIds.add(requirementId);
        java.util.Set<UUID> testIds = new java.util.HashSet<>();
        for (var hop : descendants) {
            if (hop.type() == TraceObjectType.REQUIREMENT) requirementIds.add(hop.id());
            else if (hop.type() == TraceObjectType.TEST) testIds.add(hop.id());
        }
        for (var hop : ascendants) {
            if (hop.type() == TraceObjectType.REQUIREMENT) requirementIds.add(hop.id());
            else if (hop.type() == TraceObjectType.TEST) testIds.add(hop.id());
        }

        long applicationsAffected = distinctCount("""
            SELECT count(DISTINCT a.id) FROM requirement r
            JOIN capability c ON c.id = r.capability_id JOIN application a ON a.id = c.application_id
            WHERE r.id IN (%s)""", requirementIds);
        long capabilitiesAffected = distinctCount(
            "SELECT count(DISTINCT capability_id) FROM requirement WHERE id IN (%s) AND capability_id IS NOT NULL",
            requirementIds);
        long owners = distinctCount("""
            SELECT count(DISTINCT owner_id) FROM requirement
            WHERE id IN (%s) AND owner_id IS NOT NULL""", requirementIds);
        // VYB-0464: a real team count — anyone owning, developing or testing one of
        // the affected requirements, resolved to their team memberships. One IN
        // clause, not one per role, so the placeholder list only needs binding once.
        long teams = distinctCount("""
            SELECT count(DISTINCT tm.team_id) FROM team_member tm
            JOIN requirement r ON tm.user_id = r.owner_id OR tm.user_id = r.developer_id OR tm.user_id = r.tester_id
            WHERE r.id IN (%s)""", requirementIds);
        long briefsAffected = distinctCount(
            "SELECT count(DISTINCT brief_id) FROM brief_requirement WHERE requirement_id IN (%s)", requirementIds);
        // VYB-0507: of the affected tests, how many are CI-ingested ("borrowed" from
        // the CI integration) rather than drafted directly in Vyoog (VYB-0363).
        long testsBorrowed = distinctCount(
            "SELECT count(*) FROM test_case WHERE id IN (%s) AND status = 'INGESTED'", testIds);

        return new ImpactVolume(requirementIds.size(), testIds.size(), testsBorrowed, applicationsAffected,
            capabilitiesAffected, owners, teams, briefsAffected);
    }

    private long distinctCount(String template, java.util.Set<UUID> ids) {
        if (ids.isEmpty()) return 0;
        String sql = template.formatted(placeholders(ids.size()));
        Long n = jdbc.queryForObject(sql, Long.class, ids.toArray());
        return n == null ? 0 : n;
    }
}
