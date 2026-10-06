package com.vyoog.evidence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0927: the pass rate per requirement, for the Quality screen.
 *
 * <p>For each requirement that has at least one test case verifying it (a {@code TEST --VERIFIES--> REQUIREMENT}
 * link), each such test case is judged by its <b>most recent</b> verification result at the requirement's
 * <b>current revision</b>, CI and manual runs together:
 * <ul>
 *   <li>{@code passed} / {@code failed}: that latest result is PASS / FAIL;</li>
 *   <li>{@code stale}: no result at the current revision, but one at an earlier revision (the requirement
 *       changed since, so the old result says nothing about this text);</li>
 *   <li>{@code notRun}: no result at any revision.</li>
 * </ul>
 * Not run and stale are <b>not failures</b> and never count against the rate. {@code passRate} is passed out of the
 * cases that have a result at the current revision (passed + failed), and is null when none has: it is "not
 * run", not zero. So a failure that a retest later passed no longer counts, because only the latest result does.
 * A BLOCKED case writes no verification (VYB-0925) and so appears as not run.
 *
 * <p>Read-only, derived on every call from {@code verification}: nothing is stored.
 */
@Service
public class RequirementPassRateService {

    public record PassRate(UUID requirementId, String key, String title, String status, int revision, int cases,
                            int passed, int failed, int stale, int notRun, Double passRate, Instant lastResultAt) {}

    private final JdbcTemplate jdbc;

    public RequirementPassRateService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Worst first: most failing, then most without a current result, then by key. */
    public Page<PassRate> list(String q, Pageable pageable) {
        List<Object> args = new ArrayList<>();
        String filter = "";
        if (q != null && !q.isBlank()) {
            filter = " AND (r.key ILIKE ? OR r.title ILIKE ?)";
            String like = "%" + q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like);
            args.add(like);
        }
        String base = """
            WITH linked AS (
              SELECT r.id AS requirement_id, r.key, r.title, r.status, r.revision, tl.from_id AS test_case_id
                FROM requirement r
                JOIN trace_link tl ON tl.to_type = 'REQUIREMENT' AND tl.to_id = r.id
                                  AND tl.from_type = 'TEST' AND tl.link_type = 'VERIFIES'
               WHERE true
            """ + filter + """
            ), judged AS (
              SELECT l.*,
                     (SELECT v.result FROM verification v
                       WHERE v.requirement_id = l.requirement_id AND v.test_case_id = l.test_case_id
                         AND v.requirement_revision = l.revision
                       ORDER BY v.verified_at DESC, v.id DESC LIMIT 1) AS current_result,
                     EXISTS (SELECT 1 FROM verification v
                              WHERE v.requirement_id = l.requirement_id AND v.test_case_id = l.test_case_id
                                AND v.requirement_revision < l.revision) AS has_older,
                     (SELECT max(v.verified_at) FROM verification v
                       WHERE v.requirement_id = l.requirement_id AND v.test_case_id = l.test_case_id) AS last_at
                FROM linked l
            ), summed AS (
              SELECT requirement_id, key, title, status, revision,
                     count(*) AS cases,
                     count(*) FILTER (WHERE current_result = 'PASS') AS passed,
                     count(*) FILTER (WHERE current_result = 'FAIL') AS failed,
                     count(*) FILTER (WHERE current_result IS NULL AND has_older) AS stale,
                     count(*) FILTER (WHERE current_result IS NULL AND NOT has_older) AS not_run,
                     max(last_at) AS last_at
                FROM judged GROUP BY requirement_id, key, title, status, revision
            )
            """;
        Long total = jdbc.queryForObject(base + "SELECT count(*) FROM summed", Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageable.getPageSize());
        pageArgs.add(pageable.getOffset());
        List<PassRate> rows = jdbc.query(base + """
            SELECT * FROM summed ORDER BY failed DESC, (stale + not_run) DESC, key LIMIT ? OFFSET ?
            """, (rs, i) -> {
                int passed = rs.getInt("passed"), failed = rs.getInt("failed");
                java.sql.Timestamp last = rs.getTimestamp("last_at");
                return new PassRate(rs.getObject("requirement_id", UUID.class), rs.getString("key"), rs.getString("title"),
                    rs.getString("status"), rs.getInt("revision"), rs.getInt("cases"), passed, failed, rs.getInt("stale"),
                    rs.getInt("not_run"), passed + failed == 0 ? null : (double) passed / (passed + failed),
                    last == null ? null : last.toInstant());
            }, pageArgs.toArray());
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }
}
