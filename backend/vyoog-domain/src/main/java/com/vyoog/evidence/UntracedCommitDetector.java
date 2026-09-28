package com.vyoog.evidence;

import com.vyoog.detection.Candidate;
import com.vyoog.detection.Detector;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** VYB-0315 — "untraced": a commit ingested with no {@code Requirement:} trailer. */
@Component
public class UntracedCommitDetector implements Detector {

    private final JdbcTemplate jdbc;

    public UntracedCommitDetector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String ruleKey() {
        return "untraced";
    }

    private static final String SELECT = """
        SELECT id, sha, author_email FROM ingested_commit WHERE NOT has_trailer
        """;

    private Candidate toCandidate(java.sql.ResultSet rs) throws java.sql.SQLException {
        String sha = rs.getString("sha");
        String author = rs.getString("author_email");
        return new Candidate(ruleKey(), "COMMIT", UUID.fromString(rs.getString("id")), null, 1, "crit",
            "Commit carries no requirement reference",
            "%s (%s) has no 'Requirement: KEY' trailer.".formatted(sha, author == null ? "unknown author" : author),
            "Add a Requirement: KEY trailer, or confirm this change is administrative and needs none.");
    }

    @Override
    public List<Candidate> scan() {
        return jdbc.query(SELECT, (rs, n) -> toCandidate(rs));
    }

    @Override
    public List<Candidate> scanOne(UUID objectId) {
        return jdbc.query(SELECT + " AND id = ?", (rs, n) -> toCandidate(rs), objectId);
    }
}
