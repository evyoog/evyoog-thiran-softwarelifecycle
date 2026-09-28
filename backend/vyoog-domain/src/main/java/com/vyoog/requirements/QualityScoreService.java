package com.vyoog.requirements;

import com.vyoog.detection.AmbiguousTermLexicon;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * VYB-0203: a score derived from wording, criteria and traceability — a pure
 * function so it's honestly explainable (AC2) and safe to call on every keystroke
 * (AC1) without touching the database. Persisted onto {@code requirement
 * .quality_score} on every create/edit ({@link RequirementService}) — a column that
 * existed in V001's baseline but nothing ever wrote to until now.
 */
@Service
public class QualityScoreService {

    public record Score(int total, Map<String, Integer> breakdown) {}

    public Score score(String statement, int criteriaCount, boolean hasUpstream) {
        Map<String, Integer> breakdown = new LinkedHashMap<>();
        int total = 100;

        int ambiguousTerms = statement == null ? 0 : AmbiguousTermLexicon.findIn(statement).size();
        int wordingPenalty = -10 * ambiguousTerms;
        breakdown.put("wording", wordingPenalty);
        total += wordingPenalty;

        int criteriaAdjustment = criteriaCount == 0 ? -20 : Math.min(10, criteriaCount * 3);
        breakdown.put("criteria", criteriaAdjustment);
        total += criteriaAdjustment;

        int traceabilityAdjustment = hasUpstream ? 5 : -10;
        breakdown.put("traceability", traceabilityAdjustment);
        total += traceabilityAdjustment;

        int lengthAdjustment = (statement == null || statement.strip().length() < 20) ? -15 : 0;
        breakdown.put("length", lengthAdjustment);
        total += lengthAdjustment;

        total = Math.max(0, Math.min(100, total));
        return new Score(total, breakdown);
    }
}
