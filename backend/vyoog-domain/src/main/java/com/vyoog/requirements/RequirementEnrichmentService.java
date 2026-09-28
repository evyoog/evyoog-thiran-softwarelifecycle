package com.vyoog.requirements;

import com.vyoog.ai.EmbeddingService;
import com.vyoog.detection.DetectionSweepService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0666: the AI-backed work a new requirement gets — a duplicate/conflict scan
 * through the detectors that call a model, and an embedding for similarity search —
 * split out from {@link RequirementService#create} so creating many requirements in one
 * commit does not hold that commit open for as many sequential AI round-trips as there
 * are rows.
 *
 * <p>Importing 39 requirements from a PRD template used to run {@code create()} 39 times
 * in a single transaction, and every one of those calls made up to four blocking network
 * calls — one embedding plus up to three AI-backed detectors — before the next row could
 * even start. Thirty-nine rows times four calls is the entire minute-plus wait; none of
 * it was database work. This still runs all of it, just after the requirement's own
 * transaction has committed and off the request thread, so the response comes back once
 * the requirements themselves exist rather than once every one of them has also been
 * embedded and scanned.
 *
 * <p>A single new requirement created from the New Requirement screen goes through this
 * same path and is unaffected in outcome — only in when the enrichment lands, a few
 * hundred milliseconds after the create response instead of before it.
 *
 * <p>A separate bean, not a private method on {@link RequirementService}: both
 * {@code @Async} and {@code @Transactional} are enforced through Spring's proxy, and a
 * method calling another method on {@code this} bypasses that proxy entirely — the call
 * would run synchronously, on the caller's own transaction, exactly the behaviour this
 * exists to get away from.
 */
@Service
public class RequirementEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(RequirementEnrichmentService.class);

    private final DetectionSweepService detection;
    private final EmbeddingService embeddings;

    public RequirementEnrichmentService(DetectionSweepService detection, EmbeddingService embeddings) {
        this.detection = detection;
        this.embeddings = embeddings;
    }

    /**
     * Must only be scheduled after the requirement's own transaction has committed —
     * {@link RequirementService} does this via {@code TransactionSynchronizationManager}'s
     * after-commit hook, never by calling this directly from inside its own
     * {@code @Transactional} method. Called before that commit, the detectors and the
     * embedding's own existence check would be reading a row that is not there yet.
     */
    @Async("requirementEnrichmentExecutor")
    @Transactional
    public void enrich(UUID requirementId, int revision, String statement) {
        try {
            detection.rescanObject(requirementId);
        } catch (Exception e) {
            log.warn("[detection] async rescan failed for {}: {}", requirementId, e.getMessage());
        }
        embeddings.embed(requirementId, revision, statement); // already catches its own exceptions
    }
}
