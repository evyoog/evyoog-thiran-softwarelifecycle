package com.vyoog.identity.detectors;

import com.vyoog.detection.Candidate;
import com.vyoog.detection.Detector;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * VYB-0705 — "departed-active": a user marked DEPARTED who still holds at least one
 * active (unrevoked, unexpired) grant. AC1: the finding names when the departure was
 * recorded, from {@code app_user.status_changed_at} (V007).
 */
@Component
public class DepartedAccountDetector implements Detector {

    private final JdbcTemplate jdbc;

    public DepartedAccountDetector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String ruleKey() {
        return "departed-active";
    }

    private static final String SELECT = """
        SELECT u.id, u.display_name, u.status_changed_at, count(g.id) AS active_grants
        FROM app_user u
        JOIN access_grant g ON g.user_id = u.id
          AND g.revoked_at IS NULL AND (g.expires_at IS NULL OR g.expires_at > now())
        WHERE u.status = 'DEPARTED'
        """;

    private Candidate toCandidate(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID userId = UUID.fromString(rs.getString("id"));
        String name = rs.getString("display_name");
        long activeGrants = rs.getLong("active_grants");
        java.sql.Timestamp departedAt = rs.getTimestamp("status_changed_at");
        return new Candidate(ruleKey(), "APP_USER", userId, null, 1, "crit",
            "Departed account still active",
            "%s was recorded as departed on %s and still holds %d active grant(s)."
                .formatted(name, departedAt.toInstant(), activeGrants),
            "Revoke every remaining grant for this account.");
    }

    @Override
    public List<Candidate> scan() {
        return jdbc.query(SELECT + " GROUP BY u.id, u.display_name, u.status_changed_at",
            (rs, n) -> toCandidate(rs));
    }

    @Override
    public List<Candidate> scanOne(UUID objectId) {
        return jdbc.query(SELECT + " AND u.id = ? GROUP BY u.id, u.display_name, u.status_changed_at",
            (rs, n) -> toCandidate(rs), objectId);
    }
}
