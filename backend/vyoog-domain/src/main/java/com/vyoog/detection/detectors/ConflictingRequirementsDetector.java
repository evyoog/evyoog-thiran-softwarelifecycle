package com.vyoog.detection.detectors;

import com.vyoog.ai.AiUsageTracker;
import com.vyoog.ai.LlmAdjudicator;
import com.vyoog.ai.SimilaritySearchService;
import com.vyoog.detection.Candidate;
import com.vyoog.detection.Detector;
import com.vyoog.ai.AiProviderUnavailableException;
import com.vyoog.detection.DetectorNotConfiguredException;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ConflictingRequirementsDetector.class);

    static final BigDecimal SHORTLIST_THRESHOLD = new BigDecimal("0.700");

    private final SimilaritySearchService similarity;
    private final Optional<LlmAdjudicator> adjudicator;
    private final GapRuleService gapRules;
    private final AiUsageTracker aiUsage;
    private final java.util.concurrent.atomic.AtomicBoolean announcedOff = new java.util.concurrent.atomic.AtomicBoolean(false);

    public ConflictingRequirementsDetector(SimilaritySearchService similarity, Optional<LlmAdjudicator> adjudicator,
                                            GapRuleService gapRules, AiUsageTracker aiUsage) {
        this.similarity = similarity;
        this.adjudicator = adjudicator;
        this.gapRules = gapRules;
        this.aiUsage = aiUsage;
    }

    @Override
    public boolean callsModel() {
        return true;
    }

    @Override
    public String ruleKey() {
        return "conflict";
    }

    private double shortlistThreshold() {
        BigDecimal configured = gapRules.getThreshold(ruleKey());
        return (configured != null ? configured : SHORTLIST_THRESHOLD).doubleValue();
    }

    /**
     * VYB-0911: checked before anything is queried or any AI-call budget is spent. With AI off (the
     * default) this used to be discovered one pair at a time, as a thrown exception that logged an
     * ERROR with a stack trace on every requirement write. Now the rule is reported unavailable, as
     * before, but quietly: one INFO line the first time, so an operator can still tell it is off.
     */
    private LlmAdjudicator requireAdjudicator() {
        LlmAdjudicator llm = adjudicator.filter(LlmAdjudicator::isConfigured).orElse(null);
        if (llm == null) {
            if (announcedOff.compareAndSet(false, true)) {
                log.info("[detection] conflict detection is off: no AI adjudicator is configured "
                    + "(set AI_ENABLED=true and AI_API_KEY to turn it on). Existing conflict findings are left as they are.");
            }
            throw new DetectorNotConfiguredException("No LLM adjudicator is configured — no conflict can be adjudicated");
        }
        return llm;
    }

    private List<Candidate> adjudicateAll(LlmAdjudicator llm, List<SimilaritySearchService.SimilarPair> pairs) {
        List<Candidate> out = new ArrayList<>();
        for (SimilaritySearchService.SimilarPair p : pairs) {
            if (!aiUsage.tryConsume()) break; // VYB-0620 AC1: defer the rest of this run rather than fail it
            LlmAdjudicator.Verdict verdict;
            try {
                verdict = llm.adjudicate(p.statementA(), p.statementB());
            } catch (AiProviderUnavailableException e) {
                // A configured provider that is down: the documented "unavailable" outcome, not an unexpected failure.
                throw new DetectorUnavailableException("AI adjudicator unavailable: " + e.getMessage(), e);
            }
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
        LlmAdjudicator llm = requireAdjudicator();
        return adjudicateAll(llm, similarity.allPairsAboveThreshold(shortlistThreshold(), 200));
    }

    @Override
    public List<Candidate> scanOne(UUID objectId) {
        LlmAdjudicator llm = requireAdjudicator();
        return adjudicateAll(llm, similarity.pairsInvolving(objectId, shortlistThreshold()));
    }
}
