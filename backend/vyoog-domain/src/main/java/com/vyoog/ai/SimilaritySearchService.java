package com.vyoog.ai;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0602/0604: nearest-neighbour search over {@code requirement_embedding} using
 * pgvector's own HNSW index (V001__baseline.sql) — a plain {@code ORDER BY embedding
 * <=> ? LIMIT n} is exactly what that index exists to accelerate; there's no
 * additional indexing work this class needs to do. Every query here filters to one
 * model (VYB-0604 AC1: comparing across two model versions is refused, not silently
 * allowed to produce a meaningless distance) — {@link EmbeddingService#reembedStaleModel}
 * is what actually gets a stale row back onto the current model.
 *
 * <p>The 200ms-at-100k-requirements target (VYB-0602 AC1) describes the index this
 * relies on; it has never been measured in this environment — no dataset anywhere
 * near that size exists here to measure it against.
 */
@Service
public class SimilaritySearchService {

    private final EmbeddingProvider provider;
    private final JdbcTemplate jdbc;

    public SimilaritySearchService(EmbeddingProvider provider, JdbcTemplate jdbc) {
        this.provider = provider;
        this.jdbc = jdbc;
    }

    public record Match(UUID requirementId, String key, String title, double similarity) {}

    /** Nearest neighbours of one requirement's own current embedding, same model only. */
    public List<Match> similarTo(UUID requirementId, int limit) {
        return jdbc.query("""
            SELECT r.id, r.key, r.title, 1 - (re2.embedding <=> re1.embedding) AS similarity
            FROM requirement_embedding re1
            JOIN requirement_embedding re2 ON re2.model = re1.model AND re2.requirement_id <> re1.requirement_id
            JOIN requirement r ON r.id = re2.requirement_id AND r.deleted_at IS NULL
            WHERE re1.requirement_id = ?
            ORDER BY re2.embedding <=> re1.embedding
            LIMIT ?
            """,
            (rs, n) -> new Match(UUID.fromString(rs.getString("id")), rs.getString("key"), rs.getString("title"),
                rs.getDouble("similarity")),
            requirementId, limit);
    }

    /** Ad-hoc text against the corpus — embeds the text with the currently configured provider, same model as everything stored. */
    public List<Match> similarToText(String text, int limit) {
        String literal = EmbeddingService.toVectorLiteral(provider.embed(text));
        return jdbc.query("""
            SELECT r.id, r.key, r.title, 1 - (re.embedding <=> ?::vector) AS similarity
            FROM requirement_embedding re
            JOIN requirement r ON r.id = re.requirement_id AND r.deleted_at IS NULL
            WHERE re.model = ?
            ORDER BY re.embedding <=> ?::vector
            LIMIT ?
            """,
            (rs, n) -> new Match(UUID.fromString(rs.getString("id")), rs.getString("key"), rs.getString("title"),
                rs.getDouble("similarity")),
            literal, provider.modelName(), literal, limit);
    }

    public record CrossAppPair(
        UUID requirementIdA, String keyA, UUID requirementIdB, String keyB, double similarity) {}

    /**
     * VYB-0610: candidate pairs above {@code threshold} that span two different
     * applications (AC1's default lives in {@code gap_rule.threshold} — the caller
     * supplies it). AC3: requirements sharing one capability never qualify — which a
     * "different applications" pair could never do anyway, since one capability
     * belongs to exactly one application, but the guard is explicit rather than
     * assumed.
     */
    public List<CrossAppPair> duplicatesAcrossApplications(double threshold, int limitPairs) {
        return jdbc.query("""
            SELECT r1.id AS id1, r1.key AS key1, r2.id AS id2, r2.key AS key2,
                   1 - (re2.embedding <=> re1.embedding) AS similarity
            FROM requirement_embedding re1
            JOIN requirement_embedding re2 ON re2.model = re1.model AND re1.requirement_id < re2.requirement_id
            JOIN requirement r1 ON r1.id = re1.requirement_id AND r1.deleted_at IS NULL
            JOIN requirement r2 ON r2.id = re2.requirement_id AND r2.deleted_at IS NULL
            JOIN capability c1 ON c1.id = r1.capability_id
            JOIN capability c2 ON c2.id = r2.capability_id
            WHERE c1.application_id <> c2.application_id
              AND r1.capability_id <> r2.capability_id
              AND (1 - (re2.embedding <=> re1.embedding)) >= ?
            ORDER BY similarity DESC
            LIMIT ?
            """,
            (rs, n) -> new CrossAppPair(
                UUID.fromString(rs.getString("id1")), rs.getString("key1"),
                UUID.fromString(rs.getString("id2")), rs.getString("key2"), rs.getDouble("similarity")),
            threshold, limitPairs);
    }

    public record SimilarPair(
        UUID requirementIdA, String keyA, String statementA, UUID requirementIdB, String keyB, String statementB) {}

    /**
     * VYB-0612: unlike {@link #duplicatesAcrossApplications}, no application/capability
     * exclusion — two requirements contradicting each other is exactly as interesting
     * within one capability as across two.
     */
    public List<SimilarPair> allPairsAboveThreshold(double threshold, int limitPairs) {
        return jdbc.query("""
            SELECT r1.id AS id1, r1.key AS key1, r1.statement AS s1, r2.id AS id2, r2.key AS key2, r2.statement AS s2
            FROM requirement_embedding re1
            JOIN requirement_embedding re2 ON re2.model = re1.model AND re1.requirement_id < re2.requirement_id
            JOIN requirement r1 ON r1.id = re1.requirement_id AND r1.deleted_at IS NULL
            JOIN requirement r2 ON r2.id = re2.requirement_id AND r2.deleted_at IS NULL
            WHERE (1 - (re2.embedding <=> re1.embedding)) >= ?
            ORDER BY (re2.embedding <=> re1.embedding)
            LIMIT ?
            """,
            (rs, n) -> new SimilarPair(
                UUID.fromString(rs.getString("id1")), rs.getString("key1"), rs.getString("s1"),
                UUID.fromString(rs.getString("id2")), rs.getString("key2"), rs.getString("s2")),
            threshold, limitPairs);
    }

    /** The bounded counterpart of {@link #allPairsAboveThreshold} — pairs involving one specific requirement. */
    public List<SimilarPair> pairsInvolving(UUID requirementId, double threshold) {
        return jdbc.query("""
            SELECT r1.id AS id1, r1.key AS key1, r1.statement AS s1, r2.id AS id2, r2.key AS key2, r2.statement AS s2
            FROM requirement_embedding re1
            JOIN requirement_embedding re2 ON re2.model = re1.model AND re1.requirement_id <> re2.requirement_id
            JOIN requirement r1 ON r1.id = re1.requirement_id AND r1.deleted_at IS NULL
            JOIN requirement r2 ON r2.id = re2.requirement_id AND r2.deleted_at IS NULL
            WHERE (re1.requirement_id = ? OR re2.requirement_id = ?) AND r1.id < r2.id
              AND (1 - (re2.embedding <=> re1.embedding)) >= ?
            """,
            (rs, n) -> new SimilarPair(
                UUID.fromString(rs.getString("id1")), rs.getString("key1"), rs.getString("s1"),
                UUID.fromString(rs.getString("id2")), rs.getString("key2"), rs.getString("s2")),
            requirementId, requirementId, threshold);
    }
}
