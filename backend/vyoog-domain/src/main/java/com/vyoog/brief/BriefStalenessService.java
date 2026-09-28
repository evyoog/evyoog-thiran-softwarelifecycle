package com.vyoog.brief;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0456: called from {@code RequirementService} the moment a revision bumps —
 * "within one detection cycle" (AC1) means synchronously, not on the next nightly
 * sweep. {@code brief_requirement.revision} is what was frozen at generation time;
 * once it no longer matches the requirement's current revision, every brief that
 * named it is stale.
 */
@Service
public class BriefStalenessService {

    private final JdbcTemplate jdbc;

    public BriefStalenessService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void markAffectedBriefsStale(UUID requirementId) {
        jdbc.update("""
            UPDATE brief SET stale = true WHERE id IN (
              SELECT br.brief_id FROM brief_requirement br
              JOIN requirement r ON r.id = br.requirement_id
              WHERE br.requirement_id = ? AND br.revision <> r.revision
            )
            """, requirementId);
    }

    /** VYB-0456 AC2: names which requirements moved, for the frontend's "stale because" text. */
    public java.util.List<String> movedRequirementKeys(UUID briefId) {
        return jdbc.queryForList("""
            SELECT r.key FROM brief_requirement br
            JOIN requirement r ON r.id = br.requirement_id
            WHERE br.brief_id = ? AND br.revision <> r.revision
            ORDER BY r.key
            """, String.class, briefId);
    }
}
