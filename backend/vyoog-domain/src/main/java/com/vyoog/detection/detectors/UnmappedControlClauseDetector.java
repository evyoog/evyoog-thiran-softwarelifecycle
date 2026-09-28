package com.vyoog.detection.detectors;

import com.vyoog.ai.EmbeddingProvider;
import com.vyoog.detection.Candidate;
import com.vyoog.detection.Detector;
import com.vyoog.detection.GapRuleService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * VYB-0611 — "compl": a control clause with no requirement above the mapping
 * threshold. A clause with at least one explicit {@code SATISFIES} trace link from a
 * requirement is always considered mapped, regardless of what an embedding score
 * says — a human already did the mapping, and second-guessing it with a lexical
 * stand-in for a real semantic model (see {@link EmbeddingProvider}) would be a
 * downgrade, not a check.
 *
 * <p>For everything else, this embeds each clause's text on demand and compares it
 * in Java against every requirement's stored embedding — unlike {@code
 * DuplicateAcrossApplicationsDetector} and {@code ConflictingRequirementsDetector},
 * clauses have no embedding table of their own to index, so this is a real but
 * unindexed brute-force comparison. Fine for the handful-to-low-hundreds of clauses a
 * compliance framework actually has; it would need its own indexed table to scale
 * the way requirement-to-requirement similarity already does.
 */
@Component
public class UnmappedControlClauseDetector implements Detector {

    static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("0.750");

    private final EmbeddingProvider provider;
    private final GapRuleService gapRules;
    private final JdbcTemplate jdbc;

    public UnmappedControlClauseDetector(EmbeddingProvider provider, GapRuleService gapRules, JdbcTemplate jdbc) {
        this.provider = provider;
        this.gapRules = gapRules;
        this.jdbc = jdbc;
    }

    @Override
    public String ruleKey() {
        return "compl";
    }

    private double threshold() {
        BigDecimal configured = gapRules.getThreshold(ruleKey());
        return (configured != null ? configured : DEFAULT_THRESHOLD).doubleValue();
    }

    private record ClauseRow(UUID id, String standard, String section, String text) {}
    private record RequirementVector(String key, float[] vector) {}

    private List<ClauseRow> unmappedClauses(UUID onlyClauseId) {
        String sql = """
            SELECT cl.id, cl.standard, cl.section, cl.text FROM clause cl
            WHERE NOT EXISTS (
              SELECT 1 FROM trace_link tl
              WHERE tl.to_type = 'CLAUSE' AND tl.to_id = cl.id
                AND tl.from_type = 'REQUIREMENT' AND tl.link_type = 'SATISFIES')
            """ + (onlyClauseId != null ? " AND cl.id = ?" : "");
        var mapper = (org.springframework.jdbc.core.RowMapper<ClauseRow>) (rs, n) ->
            new ClauseRow(UUID.fromString(rs.getString("id")), rs.getString("standard"),
                rs.getString("section"), rs.getString("text"));
        return onlyClauseId != null ? jdbc.query(sql, mapper, onlyClauseId) : jdbc.query(sql, mapper);
    }

    private List<RequirementVector> allRequirementVectors() {
        return jdbc.query("""
            SELECT r.key, re.embedding::text AS embedding_text FROM requirement_embedding re
            JOIN requirement r ON r.id = re.requirement_id AND r.deleted_at IS NULL
            WHERE re.model = ?
            """,
            (rs, n) -> new RequirementVector(rs.getString("key"), parseVectorLiteral(rs.getString("embedding_text"))),
            provider.modelName());
    }

    private static float[] parseVectorLiteral(String literal) {
        String inner = literal.substring(1, literal.length() - 1); // strip [ ]
        if (inner.isBlank()) return new float[0];
        String[] parts = inner.split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) v[i] = Float.parseFloat(parts[i]);
        return v;
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private List<Candidate> detect(UUID onlyClauseId) {
        List<ClauseRow> clauses = unmappedClauses(onlyClauseId);
        if (clauses.isEmpty()) return List.of();
        List<RequirementVector> requirementVectors = allRequirementVectors();
        double thr = threshold();

        return clauses.stream().map(clause -> {
            float[] clauseVector = provider.embed(clause.text());
            double best = 0;
            for (RequirementVector rv : requirementVectors) {
                best = Math.max(best, cosine(clauseVector, rv.vector()));
            }
            if (best >= thr) return null; // some requirement is close enough — treat it as mapped

            return new Candidate(ruleKey(), "CLAUSE", clause.id(), null, 1, "crit",
                "Unmapped control clause",
                "%s %s has no requirement satisfying it (best match: %.0f%%): \"%s\""
                    .formatted(clause.standard(), clause.section() == null ? "" : clause.section(),
                        best * 100, clause.text()),
                "Proposal only: \"The system shall %s.\""
                    .formatted(clause.text().replaceAll("(?i)^(the system|the organization|the vendor)\\s+(shall|must|should)\\s+", "")),
                1.0 - best, provider.modelName() + "@prompt-n/a");
        }).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public List<Candidate> scan() {
        return detect(null);
    }

    @Override
    public List<Candidate> scanOne(UUID objectId) {
        // objectId is a clause id when a clause itself changed — check just that one.
        // Otherwise it's a requirement that changed, which might newly cover a
        // previously-unmapped clause; the bounded work in that case is re-checking
        // the (typically small) whole unmapped set, not every requirement.
        return clauseExists(objectId) ? detect(objectId) : detect(null);
    }

    private boolean clauseExists(UUID id) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM clause WHERE id = ?)", Boolean.class, id);
        return Boolean.TRUE.equals(exists);
    }
}
