package com.vyoog.evidence;

import com.vyoog.detection.DetectionSweepService;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.trace.TraceGraphService;
import com.vyoog.trace.TraceLinkType;
import com.vyoog.trace.TraceObjectType;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0363: "draft a test case" as a proposal — a human, not CI, creates a
 * {@code TestCase} and a real {@code TEST --VERIFIES--> REQUIREMENT} trace link in one
 * transaction, mirroring exactly what {@code VerificationService.ensureVerifiesLink}
 * does for a CI-ingested one (same link shape, same rule), so the coverage matrix and
 * {@code CoveragePips} treat a drafted test case identically to an ingested one from
 * the moment it exists — the only difference is {@code status=DRAFT} until a real test
 * run ever verifies it. VYB-0824: this is also the only path an accepted AI-generated
 * suggestion goes through — {@link TestCaseSuggestionService} never persists anything
 * itself, so accepting a suggestion calls this method exactly like a manual draft.
 */
@Service
public class TestCaseService {

    private final TestCaseRepository testCases;
    private final TestCaseKeyAllocator keys;
    private final TraceGraphService trace;
    private final DetectionSweepService detection;
    private final AuditService audit;

    public TestCaseService(TestCaseRepository testCases, TestCaseKeyAllocator keys,
                            TraceGraphService trace, DetectionSweepService detection, AuditService audit) {
        this.testCases = testCases;
        this.keys = keys;
        this.trace = trace;
        this.detection = detection;
        this.audit = audit;
    }

    @Transactional
    public TestCase draft(String title, String description, TestCase.Category category, UUID requirementId, UUID actorId) {
        TestCase testCase = testCases.save(
            new TestCase(keys.next(), title, description, category, TestCase.Status.DRAFT, actorId));
        // TraceGraphService.createLink validates both endpoints exist via a raw
        // JdbcTemplate SELECT (TraceGraphService.requireExists) — Hibernate's write-
        // behind means the INSERT above can still be sitting unflushed when that plain
        // JDBC query runs, so it finds nothing and rejects a test case that, from the
        // caller's point of view, obviously exists. Same reasoning as
        // TraceGraphService.rescan's own `links.flush()` immediately before its raw
        // JDBC/detection calls — flush before crossing from JPA to raw SQL, not after.
        testCases.flush();
        trace.createLink(TraceObjectType.TEST, testCase.getId(), TraceObjectType.REQUIREMENT,
            requirementId, TraceLinkType.VERIFIES, actorId);
        audit.record(actorId, "test-case.drafted", "TEST_CASE", testCase.getId(), null,
            Map.of("key", testCase.getKey(), "requirementId", requirementId.toString()));
        // Same as every other write path that changes what a detector reads (VYB-0161)
        // — a new VERIFIES link changes NoVerifyDetector's/coverage's view of the
        // requirement it now points at.
        detection.rescanObject(requirementId);
        return testCase;
    }

    /**
     * VYB-0828: correcting or expanding a test case already on the register — title,
     * description and category only. Neither {@code key} nor {@code status} changes
     * here: a person editing wording doesn't turn a real test result (INGESTED) into a
     * proposal, and doesn't touch the VERIFIES link either, so no detection rescan is
     * needed — nothing a detector reads (coverage, verification state) changed.
     */
    @Transactional
    public TestCase update(UUID id, String title, String description, TestCase.Category category, UUID actorId) {
        TestCase testCase = testCases.findById(id).orElseThrow(() -> new NoSuchElementException("No such test case: " + id));
        testCase.edit(title, description, category);
        audit.record(actorId, "test-case.updated", "TEST_CASE", testCase.getId(), null,
            Map.of("key", testCase.getKey()));
        return testCase;
    }
}
