package com.vyoog.evidence;

import com.vyoog.platform.audit.AuditService;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import com.vyoog.requirements.RequirementService;
import com.vyoog.trace.TraceLink;
import com.vyoog.trace.TraceLinkRepository;
import com.vyoog.trace.TraceLinkType;
import com.vyoog.trace.TraceObjectType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0311/0312/0314: ingests CI test results and binds each to the revision actually
 * tested — quality evidence, kept for reporting (stale-evidence detection, the
 * Quality module's pass/fail history). VYB-0810: this used to be the one caller of
 * {@code RequirementService#promoteToVerifiedSystemically}, moving a requirement's own
 * status to VERIFIED on a pass. That status, and this promotion, no longer exist —
 * verifying is a decision a person makes from the "Verify" action, not something CI
 * reports back automatically. A {@link Verification} row is still recorded either way.
 */
@Service
public class VerificationService {

    private final TestCaseRepository testCases;
    private final TestRunRepository testRuns;
    private final VerificationRepository verifications;
    private final RequirementRepository requirements;
    private final TraceLinkRepository links;
    private final AuditService audit;

    public VerificationService(TestCaseRepository testCases, TestRunRepository testRuns,
                                VerificationRepository verifications, RequirementRepository requirements,
                                TraceLinkRepository links, AuditService audit) {
        this.testCases = testCases;
        this.testRuns = testRuns;
        this.verifications = verifications;
        this.requirements = requirements;
        this.links = links;
        this.audit = audit;
    }

    /** VYB-0311 AC3: the same build+source reuses the same run rather than duplicating it. */
    @Transactional
    public TestRun findOrCreateRun(String buildLabel, String source) {
        return testRuns.findByBuildLabelAndSource(buildLabel, source)
            .orElseGet(() -> testRuns.save(new TestRun(buildLabel, source)));
    }

    public record ResultInput(String testKey, String title, VerificationResult result, List<String> requirementKeys) {}

    public record IngestOutcome(
        String testKey, boolean testCaseWasUnknown, List<String> verifiedRequirementKeys) {}

    /**
     * VYB-0311 AC2: an unrecognised test key is recorded, not dropped — a {@link
     * TestCase} row is created for it with whatever title the payload carried (or the
     * key itself, if none did). VYB-0312 AC1: every {@link Verification} row freezes
     * the requirement's revision as of right now, before anything about the
     * requirement can change underneath this call.
     */
    @Transactional
    public IngestOutcome ingest(TestRun run, ResultInput in) {
        boolean wasUnknown = testCases.findByKey(in.testKey()).isEmpty();
        TestCase testCase = testCases.findByKey(in.testKey())
            .orElseGet(() -> testCases.save(new TestCase(in.testKey(), in.title() != null ? in.title() : in.testKey())));

        List<Requirement> targets = resolveTargets(testCase, in.requirementKeys());
        List<String> verifiedKeys = new ArrayList<>();

        for (Requirement r : targets) {
            // VYB-0311 AC3: an identical (run, test case, requirement) triple is a no-op.
            if (verifications.existsByTestRunIdAndTestCaseIdAndRequirementId(run.getId(), testCase.getId(), r.getId())) {
                continue;
            }
            verifications.save(new Verification(r.getId(), r.getRevision(), testCase.getId(), run.getId(), in.result()));
            verifiedKeys.add(r.getKey());
            ensureVerifiesLink(testCase.getId(), r.getId());
        }

        audit.recordService("evidence.ingested", "TEST_CASE", testCase.getId(),
            Map.of("testKey", in.testKey(), "result", in.result().name(), "requirements", verifiedKeys));
        return new IngestOutcome(in.testKey(), wasUnknown, verifiedKeys);
    }

    /** Explicit requirement keys on the payload, falling back to links already on record. */
    private List<Requirement> resolveTargets(TestCase testCase, List<String> requirementKeys) {
        if (requirementKeys != null && !requirementKeys.isEmpty()) {
            List<Requirement> found = new ArrayList<>();
            for (String key : requirementKeys) {
                requirements.findByKey(key).ifPresent(found::add);
            }
            return found;
        }
        return links.findAllByFromTypeAndFromId(TraceObjectType.TEST, testCase.getId()).stream()
            .filter(l -> l.getLinkType() == TraceLinkType.VERIFIES && l.getToType() == TraceObjectType.REQUIREMENT)
            .map(l -> requirements.findById(l.getToId()).orElse(null))
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    private void ensureVerifiesLink(UUID testCaseId, UUID requirementId) {
        boolean exists = links.existsByFromTypeAndFromIdAndToTypeAndToIdAndLinkType(
            TraceObjectType.TEST, testCaseId, TraceObjectType.REQUIREMENT, requirementId, TraceLinkType.VERIFIES);
        if (!exists) {
            links.save(new TraceLink(
                TraceObjectType.TEST, testCaseId, TraceObjectType.REQUIREMENT, requirementId,
                TraceLinkType.VERIFIES, null));
        }
    }

    /** VYB-0314: requirements whose only passing evidence is against an earlier revision. */
    public List<Requirement> staleEvidence() {
        return requirements.findAllStaleEvidence();
    }
}
