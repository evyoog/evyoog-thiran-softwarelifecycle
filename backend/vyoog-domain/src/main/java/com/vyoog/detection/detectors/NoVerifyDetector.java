package com.vyoog.detection.detectors;

import com.vyoog.detection.Candidate;
import com.vyoog.detection.Detector;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * VYB-0155 — "noverify": an approved requirement with no passing test at its current
 * revision. Reuses {@code requirement_verification_state} (V001__baseline.sql) rather
 * than reimplementing "is it verified" as a second definition that could drift from
 * the view's — there must be exactly one definition of verified (Principle 5).
 */
@Component
public class NoVerifyDetector implements Detector {

    private final JdbcTemplate jdbc;

    public NoVerifyDetector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String ruleKey() {
        return "noverify";
    }

    @Override
    public List<Candidate> scan() {
        return jdbc.query("""
            SELECT r.id, r.revision, r.key
            FROM requirement r
            JOIN requirement_verification_state rvs ON rvs.id = r.id
            WHERE r.status = 'APPROVED' AND r.deleted_at IS NULL AND NOT rvs.is_verified
            """,
            (rs, rowNum) -> new Candidate(
                ruleKey(), "REQUIREMENT", UUID.fromString(rs.getString("id")), null, rs.getInt("revision"),
                "crit", "No passing test at the current revision",
                "%s is approved but has no passing test against revision %d."
                    .formatted(rs.getString("key"), rs.getInt("revision")),
                "Run or record a test against the current revision, or route back to review."));
    }

    @Override
    public List<Candidate> scanOne(UUID objectId) {
        return jdbc.query("""
            SELECT r.id, r.revision, r.key
            FROM requirement r
            JOIN requirement_verification_state rvs ON rvs.id = r.id
            WHERE r.id = ? AND r.status = 'APPROVED' AND r.deleted_at IS NULL AND NOT rvs.is_verified
            """,
            (rs, rowNum) -> new Candidate(
                ruleKey(), "REQUIREMENT", UUID.fromString(rs.getString("id")), null, rs.getInt("revision"),
                "crit", "No passing test at the current revision",
                "%s is approved but has no passing test against revision %d."
                    .formatted(rs.getString("key"), rs.getInt("revision")),
                "Run or record a test against the current revision, or route back to review."),
            objectId);
    }
}
