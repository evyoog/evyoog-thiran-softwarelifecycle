package com.vyoog.evidence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0825: a browsable list of test cases that already exist — every prior read path
 * in this package (evidence summary, unverified/stale requirements) returns requirement
 * rows, never a {@link TestCase} row. {@code test_case} has no {@code requirement_id}
 * column of its own — the association is only the {@code TEST --VERIFIES--> REQUIREMENT}
 * trace link {@link TestCaseService#draft} creates — so the requirement each row shows
 * is found via a join through {@code trace_link}, in raw SQL per this codebase's own
 * style rule (joins/aggregates like this belong in {@code JdbcTemplate}, not JPQL).
 */
@Service
public class TestCaseQueryService {

    /** What one row of the list shows — deliberately including the requirement it verifies, not just the test case. */
    public record Row(UUID id, String key, String title, String description, String category, String status,
                       UUID requirementId, String requirementKey, String requirementTitle) {}

    /** One requirement that has at least one test case, with counts by category — the "Test cases" tab's top level. */
    public record RequirementSummaryRow(UUID requirementId, String requirementKey, String requirementTitle,
                                         long individualCount, long dependencyCount, long otherCount, long totalCount) {}

    /** One requirement's own test cases, expanded on click. */
    public record RequirementTestCaseRow(UUID id, String key, String title, String description, String category, String status) {}

    private final JdbcTemplate jdbc;

    public TestCaseQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param q free-text match against the test case's own key/title or the requirement
     *     it verifies' key/title; null or blank means no filter
     * @param status DRAFT or INGESTED; null means both
     * @implNote a test case verifying more than one requirement (nothing prevents it —
     *     {@code trace_link}'s uniqueness is per link, not per test case) shows only the
     *     earliest-created VERIFIES link, picked deterministically via {@code LIMIT 1}
     *     inside the lateral join, so a row is never duplicated on this list.
     */
    public Page<Row> list(String q, String status, Pageable pageable) {
        String like = (q == null || q.isBlank()) ? null : "%" + q.trim() + "%";
        boolean hasQ = like != null;
        boolean hasStatus = status != null && !status.isBlank();

        String where = """
            WHERE (? = false OR tc.key ILIKE ? OR tc.title ILIKE ? OR r.key ILIKE ? OR r.title ILIKE ?)
              AND (? = false OR tc.status = ?)
            """;

        List<Object> args = new ArrayList<>();
        args.add(hasQ);
        args.add(like); args.add(like); args.add(like); args.add(like);
        args.add(hasStatus);
        args.add(hasStatus ? status : "");

        String fromJoin = """
            FROM test_case tc
            LEFT JOIN LATERAL (
                SELECT to_id FROM trace_link
                WHERE from_type = 'TEST' AND from_id = tc.id AND link_type = 'VERIFIES'
                ORDER BY created_at ASC LIMIT 1
            ) tl ON true
            LEFT JOIN requirement r ON r.id = tl.to_id
            """;

        Long total = jdbc.queryForObject("SELECT count(*) " + fromJoin + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageable.getPageSize());
        pageArgs.add(pageable.getOffset());
        List<Row> content = jdbc.query("""
            SELECT tc.id, tc.key, tc.title, tc.description, tc.category, tc.status, r.id AS requirement_id, r.key AS requirement_key, r.title AS requirement_title
            """ + fromJoin + where + """
            ORDER BY tc.created_at DESC
            LIMIT ? OFFSET ?
            """,
            (rs, rowNum) -> new Row(
                UUID.fromString(rs.getString("id")), rs.getString("key"), rs.getString("title"),
                rs.getString("description"), rs.getString("category"), rs.getString("status"),
                rs.getString("requirement_id") == null ? null : UUID.fromString(rs.getString("requirement_id")),
                rs.getString("requirement_key"), rs.getString("requirement_title")),
            pageArgs.toArray());

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    /**
     * VYB-0827: unlike {@link #list}, this deliberately does NOT dedupe to one link per
     * test case — a requirement's count has to reflect every VERIFIES link that actually
     * points at it, not an arbitrary "first" one.
     *
     * @param q matches the requirement's own key/title or any of its test cases'
     */
    public Page<RequirementSummaryRow> listRequirementsWithTestCases(String q, Pageable pageable) {
        String like = (q == null || q.isBlank()) ? null : "%" + q.trim() + "%";
        boolean hasQ = like != null;

        String where = "WHERE (? = false OR r.key ILIKE ? OR r.title ILIKE ? OR tc.key ILIKE ? OR tc.title ILIKE ?)";
        List<Object> args = new ArrayList<>(List.of(hasQ));
        args.add(like); args.add(like); args.add(like); args.add(like);

        String fromJoin = """
            FROM trace_link tl
            JOIN test_case tc ON tc.id = tl.from_id AND tl.from_type = 'TEST' AND tl.link_type = 'VERIFIES'
            JOIN requirement r ON r.id = tl.to_id AND tl.to_type = 'REQUIREMENT'
            """;

        Long total = jdbc.queryForObject(
            "SELECT count(DISTINCT r.id) " + fromJoin + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageable.getPageSize());
        pageArgs.add(pageable.getOffset());
        List<RequirementSummaryRow> content = jdbc.query("""
            SELECT r.id, r.key, r.title,
                   count(*) FILTER (WHERE tc.category = 'INDIVIDUAL') AS individual_count,
                   count(*) FILTER (WHERE tc.category = 'DEPENDENCY') AS dependency_count,
                   count(*) FILTER (WHERE tc.category IS NULL) AS other_count,
                   count(*) AS total_count
            """ + fromJoin + where + """
            GROUP BY r.id, r.key, r.title
            ORDER BY r.key
            LIMIT ? OFFSET ?
            """,
            (rs, rowNum) -> new RequirementSummaryRow(
                UUID.fromString(rs.getString("id")), rs.getString("key"), rs.getString("title"),
                rs.getLong("individual_count"), rs.getLong("dependency_count"),
                rs.getLong("other_count"), rs.getLong("total_count")),
            pageArgs.toArray());

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    /** VYB-0827: one requirement's test cases, newest first — expanded on click, not paged (a requirement rarely has more than a handful). */
    public List<RequirementTestCaseRow> listForRequirement(UUID requirementId) {
        return jdbc.query("""
            SELECT tc.id, tc.key, tc.title, tc.description, tc.category, tc.status
            FROM trace_link tl
            JOIN test_case tc ON tc.id = tl.from_id AND tl.from_type = 'TEST' AND tl.link_type = 'VERIFIES'
            WHERE tl.to_id = ? AND tl.to_type = 'REQUIREMENT'
            ORDER BY tc.created_at DESC
            """,
            (rs, rowNum) -> new RequirementTestCaseRow(
                UUID.fromString(rs.getString("id")), rs.getString("key"), rs.getString("title"),
                rs.getString("description"), rs.getString("category"), rs.getString("status")),
            requirementId);
    }

    /**
     * VYB-0831: every requirement's test cases in one query — used where a caller (the
     * delivery brief) needs a whole scope's test cases at once rather than one requirement
     * at a time; a requirement absent from the returned map has none. Bound as {@code
     * text[]} with an explicit cast, same reasoning as {@code TraceGraphService.coverageFor}:
     * uuid[] inference from a Java array is unreliable across pgjdbc versions.
     */
    public Map<UUID, List<RequirementTestCaseRow>> listForRequirements(Collection<UUID> requirementIds) {
        if (requirementIds.isEmpty()) return Map.of();
        String[] ids = requirementIds.stream().map(UUID::toString).toArray(String[]::new);
        Map<UUID, List<RequirementTestCaseRow>> byRequirement = new LinkedHashMap<>();
        jdbc.query("""
            SELECT tl.to_id AS requirement_id, tc.id, tc.key, tc.title, tc.description, tc.category, tc.status
            FROM trace_link tl
            JOIN test_case tc ON tc.id = tl.from_id AND tl.from_type = 'TEST' AND tl.link_type = 'VERIFIES'
            WHERE tl.to_type = 'REQUIREMENT' AND tl.to_id = ANY(?::uuid[])
            ORDER BY tc.created_at DESC
            """,
            rs -> {
                UUID requirementId = UUID.fromString(rs.getString("requirement_id"));
                byRequirement.computeIfAbsent(requirementId, k -> new ArrayList<>()).add(new RequirementTestCaseRow(
                    UUID.fromString(rs.getString("id")), rs.getString("key"), rs.getString("title"),
                    rs.getString("description"), rs.getString("category"), rs.getString("status")));
            },
            (Object) ids);
        return byRequirement;
    }
}
