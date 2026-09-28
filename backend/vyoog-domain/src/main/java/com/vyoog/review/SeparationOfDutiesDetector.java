package com.vyoog.review;

import com.vyoog.detection.Candidate;
import com.vyoog.detection.Detector;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * VYB-0304 AC3 — "an existing violation is reported as a finding". {@link
 * ReviewService#sign} already refuses this going forward; this is the safety net for
 * whatever that refusal can't catch — an owner reassigned onto a requirement after
 * the approver had already signed it, or any row that predates the rule existing at
 * all. Reuses the same twelve-rule mechanism the rest of detection uses (V005 adds the
 * 'sod' template row), rather than a bespoke alert path for one more kind of gap.
 */
@Component
public class SeparationOfDutiesDetector implements Detector {

    private final JdbcTemplate jdbc;

    public SeparationOfDutiesDetector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String ruleKey() {
        return "sod";
    }

    private static final String SELECT = """
        SELECT DISTINCT rp.review_id, rp.user_id, r.key,
               CASE WHEN r.owner_id = rp.user_id THEN 'owner' ELSE 'author' END AS violation_kind
        FROM review_participant rp
        JOIN review_item ri ON ri.review_id = rp.review_id
        JOIN requirement r ON r.id = ri.requirement_id
        WHERE rp.role = 'APPROVER' AND rp.signed_at IS NOT NULL
          AND (r.owner_id = rp.user_id OR r.created_by = rp.user_id)
        """;

    private Candidate toCandidate(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID reviewId = UUID.fromString(rs.getString("review_id"));
        String userId = rs.getString("user_id");
        String key = rs.getString("key");
        String kind = rs.getString("violation_kind");
        return new Candidate(ruleKey(), "REVIEW", reviewId, userId, 1, "crit",
            "Approver is the requirement's " + kind,
            "%s signed as approver on a round that includes %s, which they are the %s of."
                .formatted(userId, key, kind),
            "Have a different approver re-sign, or remove this participant from the round.");
    }

    @Override
    public List<Candidate> scan() {
        return jdbc.query(SELECT, (rs, n) -> toCandidate(rs));
    }

    @Override
    public List<Candidate> scanOne(UUID objectId) {
        // objectId may be a review id or a requirement id — same dual-interpretation
        // choice SuspectLinkDetector makes, for the same reason: the caller triggering
        // a bounded rescan (a signature, or a requirement's owner changing) knows one
        // or the other, not always both.
        return jdbc.query(SELECT + " AND (rp.review_id = ? OR r.id = ?)",
            (rs, n) -> toCandidate(rs), objectId, objectId);
    }
}
