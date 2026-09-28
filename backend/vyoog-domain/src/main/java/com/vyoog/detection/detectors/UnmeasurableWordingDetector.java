package com.vyoog.detection.detectors;

import com.vyoog.detection.AmbiguousTermLexicon;
import com.vyoog.detection.Candidate;
import com.vyoog.detection.Detector;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * VYB-0160 — "ambig": wording that cannot become a pass/fail condition. AC4 ("no
 * model is called") — this is pure lexicon matching, same as the live lint endpoint
 * (VYB-0134) that shares {@link AmbiguousTermLexicon} with this detector.
 */
@Component
public class UnmeasurableWordingDetector implements Detector {

    private final JdbcTemplate jdbc;

    public UnmeasurableWordingDetector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String ruleKey() {
        return "ambig";
    }

    @Override
    public List<Candidate> scan() {
        return jdbc.query("""
            SELECT id, revision, key, statement FROM requirement WHERE deleted_at IS NULL
            """,
            (rs, rowNum) -> {
                String statement = rs.getString("statement");
                var matches = AmbiguousTermLexicon.findIn(statement);
                if (matches.isEmpty()) return null;

                String detail = "%s: %s".formatted(rs.getString("key"),
                    matches.stream().map(AmbiguousTermLexicon.Match::term).reduce((a, b) -> a + ", " + b).orElse(""));
                String suggestion = matches.stream()
                    .map(m -> "'%s' — %s".formatted(m.term(), m.suggestion()))
                    .reduce((a, b) -> a + "\n" + b).orElse("");

                return new Candidate(ruleKey(), "REQUIREMENT", UUID.fromString(rs.getString("id")), null,
                    rs.getInt("revision"), "ai", "Unmeasurable wording", detail, suggestion);
            }).stream().filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public List<Candidate> scanOne(UUID objectId) {
        return jdbc.query("""
            SELECT id, revision, key, statement FROM requirement WHERE id = ? AND deleted_at IS NULL
            """,
            (rs, rowNum) -> {
                String statement = rs.getString("statement");
                var matches = AmbiguousTermLexicon.findIn(statement);
                if (matches.isEmpty()) return null;

                String detail = "%s: %s".formatted(rs.getString("key"),
                    matches.stream().map(AmbiguousTermLexicon.Match::term).reduce((a, b) -> a + ", " + b).orElse(""));
                String suggestion = matches.stream()
                    .map(m -> "'%s' — %s".formatted(m.term(), m.suggestion()))
                    .reduce((a, b) -> a + "\n" + b).orElse("");

                return new Candidate(ruleKey(), "REQUIREMENT", UUID.fromString(rs.getString("id")), null,
                    rs.getInt("revision"), "ai", "Unmeasurable wording", detail, suggestion);
            }, objectId).stream().filter(java.util.Objects::nonNull).toList();
    }
}
