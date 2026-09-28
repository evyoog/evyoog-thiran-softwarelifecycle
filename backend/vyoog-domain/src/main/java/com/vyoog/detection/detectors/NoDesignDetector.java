package com.vyoog.detection.detectors;

import com.vyoog.detection.Candidate;
import com.vyoog.detection.Detector;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** VYB-0157 — "nodesign": no design node implements this requirement. */
@Component
public class NoDesignDetector implements Detector {

    private final JdbcTemplate jdbc;

    public NoDesignDetector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String ruleKey() {
        return "nodesign";
    }

    @Override
    public List<Candidate> scan() {
        return jdbc.query("""
            SELECT r.id, r.revision, r.key
            FROM requirement r
            JOIN requirement_coverage rc ON rc.id = r.id
            WHERE r.deleted_at IS NULL AND NOT rc.has_design
            """,
            (rs, rowNum) -> new Candidate(
                ruleKey(), "REQUIREMENT", UUID.fromString(rs.getString("id")), null, rs.getInt("revision"),
                "high", "No downstream design",
                "%s has no design node implementing it.".formatted(rs.getString("key")),
                "Attach it to a design node, or confirm no design is expected for this requirement."));
    }

    @Override
    public List<Candidate> scanOne(UUID objectId) {
        return jdbc.query("""
            SELECT r.id, r.revision, r.key
            FROM requirement r
            JOIN requirement_coverage rc ON rc.id = r.id
            WHERE r.id = ? AND r.deleted_at IS NULL AND NOT rc.has_design
            """,
            (rs, rowNum) -> new Candidate(
                ruleKey(), "REQUIREMENT", UUID.fromString(rs.getString("id")), null, rs.getInt("revision"),
                "high", "No downstream design",
                "%s has no design node implementing it.".formatted(rs.getString("key")),
                "Attach it to a design node, or confirm no design is expected for this requirement."),
            objectId);
    }
}
