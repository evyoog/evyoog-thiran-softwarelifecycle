package com.vyoog.detection.detectors;

import com.vyoog.detection.Candidate;
import com.vyoog.detection.Detector;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** VYB-0158 — "noac": a requirement with no acceptance criteria at all. */
@Component
public class NoAcceptanceCriteriaDetector implements Detector {

    private final JdbcTemplate jdbc;

    public NoAcceptanceCriteriaDetector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String ruleKey() {
        return "noac";
    }

    @Override
    public List<Candidate> scan() {
        return jdbc.query("""
            SELECT r.id, r.revision, r.key
            FROM requirement r
            WHERE r.deleted_at IS NULL
              AND NOT EXISTS (SELECT 1 FROM acceptance_criterion ac WHERE ac.requirement_id = r.id)
            """,
            (rs, rowNum) -> new Candidate(
                ruleKey(), "REQUIREMENT", UUID.fromString(rs.getString("id")), null, rs.getInt("revision"),
                "high", "No acceptance criteria",
                "%s has nothing to write a test against.".formatted(rs.getString("key")),
                "Add at least one acceptance criterion."));
    }

    @Override
    public List<Candidate> scanOne(UUID objectId) {
        return jdbc.query("""
            SELECT r.id, r.revision, r.key
            FROM requirement r
            WHERE r.id = ? AND r.deleted_at IS NULL
              AND NOT EXISTS (SELECT 1 FROM acceptance_criterion ac WHERE ac.requirement_id = r.id)
            """,
            (rs, rowNum) -> new Candidate(
                ruleKey(), "REQUIREMENT", UUID.fromString(rs.getString("id")), null, rs.getInt("revision"),
                "high", "No acceptance criteria",
                "%s has nothing to write a test against.".formatted(rs.getString("key")),
                "Add at least one acceptance criterion."),
            objectId);
    }
}
