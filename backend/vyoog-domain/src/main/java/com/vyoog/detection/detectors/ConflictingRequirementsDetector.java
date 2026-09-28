package com.vyoog.detection.detectors;

import com.vyoog.ai.AiUsageTracker;
import com.vyoog.ai.LlmAdjudicator;
import com.vyoog.ai.SimilaritySearchService;
import com.vyoog.detection.Candidate;
import com.vyoog.detection.Detector;
import com.vyoog.detection.DetectorUnavailableException;
import com.vyoog.detection.GapRuleService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * VYB-0612 — "conflict": shortlist by similarity, then adjudicate with a language
 * model. AC1: no finding is ever created without an actual adjudication — if no
 * {@link LlmAdjudicator} bean exists (true in this environment; see
 * BUILD-REGISTER.md), this reports itself unavailable and creates nothing at all,
 * rather than guessing. AC3: model and prompt version travel on every finding via
 * {@link Candidate#model}.
 */
@Component
public class ConflictingRequirementsDetector implements Detector {

    static final BigDecimal SHORTLIST_THRESHOLD = new BigDecimal("0.700");

    private final SimilaritySearchService similarity;
    private final Optional<LlmAdjudicator> adjudicator;
    private final GapRuleService gapRules;
    private final AiUsageTracker aiUsage;

    public ConflictingRequirementsDetector(SimilaritySearchService similarity, Optional<LlmAdjudicator> adjudicator,
                                            GapRuleService gapRules, AiUsageTracker aiUsage) {
        this.similarity = similarity;
        this.adjudicator = adjudicator;
        this.gapRules = gapRules;
        this.aiUsage = aiUsage;
    }

    @Override
    public String ruleKey() {
        return "conflict";
    }

    private double shortlistThreshold() {
        BigDecimal configured = gapRules.getThreshold(ruleKey());
        return (configured != null ? configured : SHORTLIST_THRESHOLD).doubleValue();
    }

    private List<Candidate> adjudicateAll(List<SimilaritySearchService.SimilarPair> pairs) {
        LlmAdjudicator llm = adjudicator.orElseThrow(() ->
            new DetectorUnavailableException("No LLM adjudicator is configured — no conflict can be adjudicated"));

        List<Candidate> out = new ArrayList<>();
        for (SimilaritySearchService.SimilarPair p : pairs) {
            if (!aiUsage.tryConsume()) break; // VYB-0620 AC1: defer the rest of this run rather than fail it
            LlmAdjudicator.Verdict verdict = llm.adjudicate(p.statementA(), p.statementB());
            if (!verdict.contradicts()) continue; // AC1: adjudicated, and the answer was no — not a finding
            out.add(new Candidate(ruleKey(), "REQUIREMENT", p.requirementIdA(), p.requirementIdB().toString(), 1, "crit",
                "Contradicts " + p.keyB(),
                "%s and %s: %s".formatted(p.keyA(), p.keyB(), verdict.explanation()), // AC2: names the nature of the contradiction
                "Resolve which requirement is correct, or scope both so they no longer overlap.",
                verdict.confidence(), llm.modelAndPromptVersion()));
        }
        return out;
    }

    @Override
    public List<Candidate> scan() {
        return adjudicateAll(similarity.allPairsAboveThreshold(shortlistThreshold(), 200));
    }

    @Override
    public List<Candidate> scanOne(UUID objectId) {
        return adjudicateAll(similarity.pairsInvolving(objectId, shortlistThreshold()));
    }
}
