package com.vyoog.identity.detectors;

import com.vyoog.detection.Candidate;
import com.vyoog.detection.Detector;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * VYB-0713 — "stale-key": a service account whose current key is older than the
 * configured threshold (AC1: {@code app_config.stale_key_age_days}, VYB-0713). The
 * current key's age is measured from {@code rotated_at} when the key has ever been
 * rotated (VYB-0712), otherwise from {@code key_issued_at} — rotating resets the
 * clock on purpose, since that's what a rotation is for.
 */
@Component
public class StaleServiceAccountKeyDetector implements Detector {

    private final JdbcTemplate jdbc;

    public StaleServiceAccountKeyDetector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String ruleKey() {
        return "stale-key";
    }

    private static final String SELECT = """
        SELECT id, name, COALESCE(rotated_at, key_issued_at) AS current_key_issued_at
        FROM service_account
        """;

    private record Row(UUID id, String name, Instant issuedAt) {}

    private Row toRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(UUID.fromString(rs.getString("id")), rs.getString("name"),
            rs.getTimestamp("current_key_issued_at").toInstant());
    }

    private Candidate toCandidate(Row row, long ageDays, int thresholdDays) {
        return new Candidate(ruleKey(), "SERVICE_ACCOUNT", row.id(), null, 1, "high",
            "Stale service account key",
            "%s's current key is %d day(s) old (threshold: %d).".formatted(row.name(), ageDays, thresholdDays),
            "Rotate this account's key.");
    }

    private int threshold() {
        Integer t = jdbc.queryForObject("SELECT stale_key_age_days FROM app_config WHERE id = 1", Integer.class);
        return t == null ? 90 : t;
    }

    private List<Candidate> detect(List<Row> rows) {
        int thresholdDays = threshold();
        List<Candidate> out = new java.util.ArrayList<>();
        for (Row row : rows) {
            long ageDays = Duration.between(row.issuedAt(), Instant.now()).toDays();
            if (ageDays > thresholdDays) out.add(toCandidate(row, ageDays, thresholdDays));
        }
        return out;
    }

    @Override
    public List<Candidate> scan() {
        return detect(jdbc.query(SELECT, (rs, n) -> toRow(rs)));
    }

    @Override
    public List<Candidate> scanOne(UUID objectId) {
        return detect(jdbc.query(SELECT + " WHERE id = ?", (rs, n) -> toRow(rs), objectId));
    }
}
