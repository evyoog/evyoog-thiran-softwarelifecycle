package com.vyoog.detection.detectors;

import com.vyoog.ai.SimilaritySearchService;
import com.vyoog.detection.Candidate;
import com.vyoog.detection.Detector;
import com.vyoog.detection.GapRuleService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * VYB-0610 — "dup": two requirements in different applications whose embeddings
 * exceed the configured similarity threshold. AC2 both requirements are named in the
 * finding's detail; AC3 same-capability pairs are excluded (enforced twice — see
 * {@link SimilaritySearchService#duplicatesAcrossApplications}).
 */
@Component
public class DuplicateAcrossApplicationsDetector implements Detector {

    /** VYB-0610 AC1: the default — {@link GapRuleService#setThreshold} overrides it per rule. */
    static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("0.900");

    private final SimilaritySearchService similarity;
    private final GapRuleService gapRules;
    private final JdbcTemplate jdbc;

    public DuplicateAcrossApplicationsDetector(SimilaritySearchService similarity, GapRuleService gapRules,
                                                JdbcTemplate jdbc) {
        this.similarity = similarity;
        this.gapRules = gapRules;
        this.jdbc = jdbc;
    }

    @Override
    public String ruleKey() {
        return "dup";
    }

    private double threshold() {
        BigDecimal configured = gapRules.getThreshold(ruleKey());
        return (configured != null ? configured : DEFAULT_THRESHOLD).doubleValue();
    }

    private Candidate toCandidate(SimilaritySearchService.CrossAppPair pair) {
        // No AI-budget check here: this reads embeddings already computed and stored
        // by EmbeddingService (which is where the actual per-call budget applies,
        // VYB-0620) — a pgvector similarity query over existing rows isn't a model
        // call in its own right.
        String discriminator = pair.requirementIdA() + "|" + pair.requirementIdB();
        return new Candidate(ruleKey(), "REQUIREMENT", pair.requirementIdA(), discriminator, 1, "ai",
            "Possible duplicate of " + pair.keyB(),
            "%s and %s are %.0f%% similar and live in different applications."
                .formatted(pair.keyA(), pair.keyB(), pair.similarity() * 100),
            "Compare both; if they're the same rule, link one as derived from the other and retire the duplicate.",
            pair.similarity(), "local-hashing-v1@prompt-n/a");
    }

    @Override
    public List<Candidate> scan() {
        return similarity.duplicatesAcrossApplications(threshold(), 500).stream().map(this::toCandidate).toList();
    }

    @Override
    public List<Candidate> scanOne(UUID objectId) {
        // Bounded: pairs where either side is this requirement, above threshold, cross-application.
        var pairs = jdbc.query("""
            SELECT r1.id AS id1, r1.key AS key1, r2.id AS id2, r2.key AS key2,
                   1 - (re2.embedding <=> re1.embedding) AS similarity
            FROM requirement_embedding re1
            JOIN requirement_embedding re2 ON re2.model = re1.model AND re1.requirement_id <> re2.requirement_id
            JOIN requirement r1 ON r1.id = re1.requirement_id AND r1.deleted_at IS NULL
            JOIN requirement r2 ON r2.id = re2.requirement_id AND r2.deleted_at IS NULL
            JOIN capability c1 ON c1.id = r1.capability_id
            JOIN capability c2 ON c2.id = r2.capability_id
            WHERE (re1.requirement_id = ? OR re2.requirement_id = ?)
              AND r1.id < r2.id
              AND c1.application_id <> c2.application_id
              AND r1.capability_id <> r2.capability_id
              AND (1 - (re2.embedding <=> re1.embedding)) >= ?
            """,
            (rs, n) -> new SimilaritySearchService.CrossAppPair(
                UUID.fromString(rs.getString("id1")), rs.getString("key1"),
                UUID.fromString(rs.getString("id2")), rs.getString("key2"), rs.getDouble("similarity")),
            objectId, objectId, threshold());
        return pairs.stream().map(this::toCandidate).toList();
    }
}
