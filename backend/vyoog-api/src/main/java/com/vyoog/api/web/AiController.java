package com.vyoog.api.web;

import com.vyoog.identity.AccessRule;

import com.vyoog.api.config.RequiresAccess;

import com.vyoog.ai.AiUsageReport;
import com.vyoog.ai.AiUsageTracker;
import com.vyoog.ai.EmbeddingProvider;
import com.vyoog.ai.EmbeddingService;
import com.vyoog.ai.SimilaritySearchService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * VYB-0600–0604: the embedding-based similarity search the "dup"/"conflict"/"compl"
 * detectors use internally, exposed for inspection. This is deliberately separate
 * from {@code RequirementController#similar} (VYB-0135, Phase 1's pg_trgm-based
 * lexical search wired into the authoring screen's live duplicate panel) — two
 * different techniques answering a similar question; neither replaces the other.
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final EmbeddingProvider provider;
    private final EmbeddingService embeddingService;
    private final SimilaritySearchService similarity;
    private final AiUsageTracker aiUsage;
    private final AiUsageReport usageReport;

    public AiController(EmbeddingProvider provider, EmbeddingService embeddingService,
                         SimilaritySearchService similarity, AiUsageTracker aiUsage, AiUsageReport usageReport) {
        this.provider = provider;
        this.embeddingService = embeddingService;
        this.similarity = similarity;
        this.aiUsage = aiUsage;
        this.usageReport = usageReport;
    }

    public record AiUsageView(int used, int limit) {}

    /** VYB-0620 AC2: usage is reported — the one thing the per-run budget's own mechanism didn't yet expose outside the JVM that ran the sweep. */
    @GetMapping("/usage")
    public AiUsageView usage() {
        return new AiUsageView(aiUsage.used(), aiUsage.limit());
    }

    /**
     * VYB-0939 (F30): tokens and calls used, by day, purpose, prompt version and model, with the budget beside them. Any signed-in
     * person may read it; it has no per-person figures and no money. {@code days} is how many UTC days the chart covers (1–366).
     */
    @GetMapping("/usage/summary")
    public AiUsageReport.Summary usageSummary(@RequestParam(defaultValue = "30") int days) {
        return usageReport.summary(days);
    }

    public record ModelInfo(String modelName, int dimensions) {}
    public record MatchView(String requirementId, String key, String title, double similarity) {}

    @GetMapping("/embedding-model")
    public ModelInfo currentModel() {
        return new ModelInfo(provider.modelName(), provider.dimensions());
    }

    @GetMapping("/similar")
    public List<MatchView> similarTo(@RequestParam UUID requirementId, @RequestParam(defaultValue = "10") int limit) {
        return similarity.similarTo(requirementId, limit).stream()
            .map(m -> new MatchView(m.requirementId().toString(), m.key(), m.title(), m.similarity())).toList();
    }

    /** VYB-0604: re-embeds whatever the corpus has on a stale model, bounded by the per-run AI-call budget. */
    // VYB-0906: a bulk, costly operation.
    @RequiresAccess(value = AccessRule.ADMIN)
    @PostMapping("/reembed-stale")
    public int reembedStale() {
        return embeddingService.reembedStaleModel();
    }
}
