package com.vyoog.detection.detectors;

import com.vyoog.detection.Candidate;
import com.vyoog.detection.Detector;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * VYB-0159 — "suspect": a link whose upstream has moved past the revision it was last
 * reviewed at (VYB-0141). Keyed on the *link*, not the requirement — the same
 * requirement can have several links, only some of which have gone stale.
 *
 * <p>Only covers a REQUIREMENT upstream today, same limitation as {@code
 * TraceGraphService.reviewLink} — other object types have no revision concept yet.
 */
@Component
public class SuspectLinkDetector implements Detector {

    private final JdbcTemplate jdbc;

    public SuspectLinkDetector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String ruleKey() {
        return "suspect";
    }

    @Override
    public List<Candidate> scan() {
        return jdbc.query("""
            SELECT tl.id AS link_id, r.key AS upstream_key, r.revision AS upstream_revision,
                   tl.reviewed_at_revision
            FROM trace_link tl
            JOIN requirement r ON r.id = tl.from_id AND tl.from_type = 'REQUIREMENT'
            WHERE tl.reviewed_at_revision IS NOT NULL AND tl.reviewed_at_revision < r.revision
            """,
            (rs, rowNum) -> new Candidate(
                ruleKey(), "TRACE_LINK", UUID.fromString(rs.getString("link_id")), null,
                rs.getInt("upstream_revision"), "high", "Upstream changed after this link was reviewed",
                "%s moved to revision %d; this link was last reviewed at revision %d."
                    .formatted(rs.getString("upstream_key"), rs.getInt("upstream_revision"),
                               rs.getInt("reviewed_at_revision")),
                "Review the link against the current revision."));
    }

    /**
     * Accepts either a trace-link id or an upstream-requirement id — a revision bump
     * can make several links suspect at once, so this is bounded to "everything
     * touching this one changed thing", not necessarily a single row.
     */
    @Override
    public List<Candidate> scanOne(UUID objectId) {
        return jdbc.query("""
            SELECT tl.id AS link_id, r.key AS upstream_key, r.revision AS upstream_revision,
                   tl.reviewed_at_revision
            FROM trace_link tl
            JOIN requirement r ON r.id = tl.from_id AND tl.from_type = 'REQUIREMENT'
            WHERE (tl.id = ? OR r.id = ?)
              AND tl.reviewed_at_revision IS NOT NULL AND tl.reviewed_at_revision < r.revision
            """,
            (rs, rowNum) -> new Candidate(
                ruleKey(), "TRACE_LINK", UUID.fromString(rs.getString("link_id")), null,
                rs.getInt("upstream_revision"), "high", "Upstream changed after this link was reviewed",
                "%s moved to revision %d; this link was last reviewed at revision %d."
                    .formatted(rs.getString("upstream_key"), rs.getInt("upstream_revision"),
                               rs.getInt("reviewed_at_revision")),
                "Review the link against the current revision."),
            objectId, objectId);
    }
}
