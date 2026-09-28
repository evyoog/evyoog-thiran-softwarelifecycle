package com.vyoog.trace;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0147: requirement-by-test coverage for one application, four cell states
 * (AC1). A cell exists for every (requirement, test) pair that has a {@code TEST
 * VERIFIES REQUIREMENT} trace link — this is deliberately sparse, not a dense
 * requirement×test grid, since most requirement/test pairs in a real application
 * have no relationship to report a state for at all.
 */
@Service
public class CoverageMatrixService {

    private final JdbcTemplate jdbc;

    public CoverageMatrixService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public enum CellState { VERIFIED, LINKED_NOT_RUN, SUSPECT }

    public record Cell(UUID requirementId, String requirementKey, UUID testCaseId, String testCaseKey, CellState state) {}
    public record TestTotal(UUID testCaseId, String testCaseKey, long verified, long linkedNotRun, long suspect) {}
    public record Matrix(List<Cell> cells, List<TestTotal> testTotals) {}

    public Matrix forApplication(UUID applicationId) {
        List<Cell> cells = jdbc.query("""
            SELECT r.id AS req_id, r.key AS req_key, r.revision, tl.reviewed_at_revision,
                   t.id AS test_id, t.key AS test_key,
                   EXISTS (
                     SELECT 1 FROM verification v
                     WHERE v.requirement_id = r.id AND v.requirement_revision = r.revision
                       AND v.test_case_id = t.id AND v.result = 'PASS'
                   ) AS has_pass
            FROM trace_link tl
            JOIN requirement r ON r.id = tl.to_id AND tl.to_type = 'REQUIREMENT'
            JOIN test_case t ON t.id = tl.from_id AND tl.from_type = 'TEST'
            JOIN capability c ON c.id = r.capability_id
            WHERE tl.link_type = 'VERIFIES' AND c.application_id = ? AND r.deleted_at IS NULL
            """,
            (rs, n) -> {
                int revision = rs.getInt("revision");
                Integer reviewedAt = rs.getObject("reviewed_at_revision") == null ? null : rs.getInt("reviewed_at_revision");
                CellState state = (reviewedAt != null && reviewedAt < revision) ? CellState.SUSPECT
                    : rs.getBoolean("has_pass") ? CellState.VERIFIED : CellState.LINKED_NOT_RUN;
                return new Cell(UUID.fromString(rs.getString("req_id")), rs.getString("req_key"),
                    UUID.fromString(rs.getString("test_id")), rs.getString("test_key"), state);
            }, applicationId);

        Map<UUID, List<Cell>> byTest = cells.stream().collect(Collectors.groupingBy(Cell::testCaseId));
        List<TestTotal> totals = byTest.entrySet().stream()
            .map(e -> new TestTotal(e.getKey(), e.getValue().get(0).testCaseKey(),
                e.getValue().stream().filter(c -> c.state() == CellState.VERIFIED).count(),
                e.getValue().stream().filter(c -> c.state() == CellState.LINKED_NOT_RUN).count(),
                e.getValue().stream().filter(c -> c.state() == CellState.SUSPECT).count()))
            .sorted(java.util.Comparator.comparing(TestTotal::testCaseKey))
            .toList();

        return new Matrix(cells, totals);
    }
}
