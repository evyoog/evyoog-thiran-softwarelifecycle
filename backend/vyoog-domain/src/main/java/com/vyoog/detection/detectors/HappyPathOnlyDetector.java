package com.vyoog.detection.detectors;

import com.vyoog.detection.Candidate;
import com.vyoog.detection.Detector;
import com.vyoog.detection.FailureModeLexicon;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * VYB-0613 — "errpath": a behavioural requirement silent on what happens when the
 * normal path fails. AC1: the rule-based lexicon fallback, since no classifier is
 * configured here (it's the only implementation, not a fallback-in-name-only). AC2:
 * only FUNCTIONAL and INTERFACE requirements describe system behaviour in response to
 * an input in the first place — the other six types (a business rule, a data
 * definition, a report, a non-functional, a security or compliance statement) aren't
 * "silent on failure" in the same sense, so they're excluded rather than flagged for
 * something that doesn't apply to them.
 */
@Component
public class HappyPathOnlyDetector implements Detector {

    private static final List<String> BEHAVIOURAL_TYPES = List.of("FUNCTIONAL", "INTERFACE");

    private final JdbcTemplate jdbc;

    public HappyPathOnlyDetector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String ruleKey() {
        return "errpath";
    }

    private static final String SELECT = """
        SELECT r.id, r.revision, r.key, r.statement,
               array_agg(ac.text) FILTER (WHERE ac.text IS NOT NULL) AS criteria
        FROM requirement r
        LEFT JOIN acceptance_criterion ac ON ac.requirement_id = r.id
        WHERE r.deleted_at IS NULL AND r.type = ANY(?)
        GROUP BY r.id, r.revision, r.key, r.statement
        """;

    private Candidate toCandidate(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<String> texts = new ArrayList<>();
        texts.add(rs.getString("statement"));
        java.sql.Array arr = rs.getArray("criteria");
        if (arr != null) {
            for (Object o : (Object[]) arr.getArray()) if (o != null) texts.add((String) o);
        }
        if (FailureModeLexicon.mentionsFailureHandling(texts)) return null;

        return new Candidate(ruleKey(), "REQUIREMENT", UUID.fromString(rs.getString("id")), null,
            rs.getInt("revision"), "ai", "Silent on failure",
            "%s describes what happens when things go right, but neither the statement nor its acceptance criteria mention what happens when they don't."
                .formatted(rs.getString("key")),
            "Add an acceptance criterion for at least one failure mode (invalid input, timeout, conflict, unauthorized — whichever applies).");
    }

    private java.sql.Array behaviouralTypes(java.sql.Connection con) throws java.sql.SQLException {
        return con.createArrayOf("text", BEHAVIOURAL_TYPES.toArray());
    }

    @Override
    public List<Candidate> scan() {
        return jdbc.query(con -> {
            var ps = con.prepareStatement(SELECT);
            ps.setArray(1, behaviouralTypes(con));
            return ps;
        }, (rs, n) -> toCandidate(rs)).stream().filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public List<Candidate> scanOne(UUID objectId) {
        String sql = SELECT.replace("WHERE r.deleted_at IS NULL AND r.type = ANY(?)",
            "WHERE r.deleted_at IS NULL AND r.type = ANY(?) AND r.id = ?");
        return jdbc.query(con -> {
            var ps = con.prepareStatement(sql);
            ps.setArray(1, behaviouralTypes(con));
            ps.setObject(2, objectId);
            return ps;
        }, (rs, n) -> toCandidate(rs)).stream().filter(java.util.Objects::nonNull).toList();
    }
}
