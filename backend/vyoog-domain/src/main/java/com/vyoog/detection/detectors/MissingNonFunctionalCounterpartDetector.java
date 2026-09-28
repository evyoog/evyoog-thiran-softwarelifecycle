package com.vyoog.detection.detectors;

import com.vyoog.detection.Candidate;
import com.vyoog.detection.Detector;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * VYB-0614 — "nonfr": a capability with functional requirements but no
 * non-functional peer at all. AC1: purely graph-based — one aggregate query over
 * {@code requirement.type} per capability, no model of any kind (the {@code
 * gap_rule_template} seed labels this rule's technique "LLM"; the actual
 * implementation the AC demands is graph-based, so that's what this is — see
 * BUILD-REGISTER.md for the discrepancy). AC2: the suggested counterpart text is
 * exactly that, a proposal — nothing here or anywhere else in this codebase writes a
 * requirement from a suggestion without VYB-0619's explicit accept action.
 */
@Component
public class MissingNonFunctionalCounterpartDetector implements Detector {

    private final JdbcTemplate jdbc;

    public MissingNonFunctionalCounterpartDetector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String ruleKey() {
        return "nonfr";
    }

    private static final String SELECT = """
        SELECT c.id, c.name,
               (SELECT r.title FROM requirement r
                 WHERE r.capability_id = c.id AND r.type = 'FUNCTIONAL' AND r.deleted_at IS NULL
                 ORDER BY r.key LIMIT 1) AS example_title
        FROM capability c
        WHERE c.archived_at IS NULL
          AND EXISTS (SELECT 1 FROM requirement r WHERE r.capability_id = c.id AND r.type = 'FUNCTIONAL' AND r.deleted_at IS NULL)
          AND NOT EXISTS (SELECT 1 FROM requirement r WHERE r.capability_id = c.id AND r.type = 'NON_FUNCTIONAL' AND r.deleted_at IS NULL)
        """;

    private Candidate toCandidate(java.sql.ResultSet rs) throws java.sql.SQLException {
        String name = rs.getString("name");
        String example = rs.getString("example_title");
        return new Candidate(ruleKey(), "CAPABILITY", UUID.fromString(rs.getString("id")), null, 1, "ai",
            "No non-functional peer",
            "Capability '%s' has functional requirements (e.g. \"%s\") but none describing performance, security, availability or another quality attribute."
                .formatted(name, example),
            "Proposal only — consider drafting a non-functional requirement for this capability (e.g. a response-time, availability or security constraint).");
    }

    @Override
    public List<Candidate> scan() {
        return jdbc.query(SELECT, (rs, n) -> toCandidate(rs));
    }

    @Override
    public List<Candidate> scanOne(UUID objectId) {
        // objectId may be the capability itself, or a requirement whose capability just changed.
        return jdbc.query(SELECT + " AND (c.id = ? OR c.id = (SELECT capability_id FROM requirement WHERE id = ?))",
            (rs, n) -> toCandidate(rs), objectId, objectId);
    }
}
