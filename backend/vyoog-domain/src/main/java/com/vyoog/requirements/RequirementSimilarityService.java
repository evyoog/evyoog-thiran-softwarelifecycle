package com.vyoog.requirements;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0135: existing requirements similar to a supplied statement. AC3 — phase one
 * uses {@code pg_trgm} trigram similarity, not embeddings (that's Phase 4's
 * VYB-0602). Also backs VYB-0204 (duplicate detection while authoring) and VYB-0637
 * (duplicate candidates flagged against the register) — one similarity primitive,
 * not three copies of near-identical SQL.
 */
@Service
public class RequirementSimilarityService {

    private final JdbcTemplate jdbc;

    public RequirementSimilarityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Match(UUID id, String key, String title, String statement, double score) {}

    /**
     * {@code %} is pg_trgm's "similar enough to bother scoring" operator (default
     * threshold 0.3) — it's what lets this use the existing GIN trigram index on
     * {@code statement} instead of scoring every row in the table.
     */
    public List<Match> findSimilar(String statement, int limit) {
        return jdbc.query("""
            SELECT id, key, title, statement, similarity(statement, ?) AS score
            FROM requirement
            WHERE deleted_at IS NULL AND statement % ?
            ORDER BY score DESC
            LIMIT ?
            """,
            (rs, rowNum) -> new Match(
                UUID.fromString(rs.getString("id")), rs.getString("key"), rs.getString("title"),
                rs.getString("statement"), rs.getDouble("score")),
            statement, statement, limit);
    }
}
