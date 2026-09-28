package com.vyoog.detection.detectors;

import com.vyoog.detection.Candidate;
import com.vyoog.detection.Detector;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * VYB-0156 — "orphan": a requirement with no upstream link of a satisfying type
 * (SATISFIES/DERIVES). Reuses {@code requirement_coverage.has_upstream}.
 */
@Component
public class OrphanDetector implements Detector {

    private final JdbcTemplate jdbc;

    public OrphanDetector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String ruleKey() {
        return "orphan";
    }

    @Override
    public List<Candidate> scan() {
        return jdbc.query("""
            SELECT r.id, r.revision, r.key
            FROM requirement r
            JOIN requirement_coverage rc ON rc.id = r.id
            WHERE r.deleted_at IS NULL AND NOT rc.has_upstream
            """,
            (rs, rowNum) -> new Candidate(
                ruleKey(), "REQUIREMENT", UUID.fromString(rs.getString("id")), null, rs.getInt("revision"),
                "crit", "No upstream need",
                "%s has no need or parent requirement satisfying it.".formatted(rs.getString("key")),
                "Link it to the need or requirement it derives from, or confirm it's foundational."));
    }

    @Override
    public List<Candidate> scanOne(UUID objectId) {
        return jdbc.query("""
            SELECT r.id, r.revision, r.key
            FROM requirement r
            JOIN requirement_coverage rc ON rc.id = r.id
            WHERE r.id = ? AND r.deleted_at IS NULL AND NOT rc.has_upstream
            """,
            (rs, rowNum) -> new Candidate(
                ruleKey(), "REQUIREMENT", UUID.fromString(rs.getString("id")), null, rs.getInt("revision"),
                "crit", "No upstream need",
                "%s has no need or parent requirement satisfying it.".formatted(rs.getString("key")),
                "Link it to the need or requirement it derives from, or confirm it's foundational."),
            objectId);
    }
}
