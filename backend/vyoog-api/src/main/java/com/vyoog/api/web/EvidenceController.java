package com.vyoog.api.web;

import com.vyoog.evidence.TestCaseRepository;
import com.vyoog.evidence.Verification;
import com.vyoog.evidence.VerificationRepository;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** VYB-0314/0362: the read side of test evidence — ingestion itself is {@code CiIngestController}. */
@RestController
@RequestMapping("/api/v1/evidence")
public class EvidenceController {

    private final RequirementRepository requirements;
    private final TestCaseRepository testCases;
    private final VerificationRepository verifications;

    public EvidenceController(RequirementRepository requirements, TestCaseRepository testCases,
                               VerificationRepository verifications) {
        this.requirements = requirements;
        this.testCases = testCases;
        this.verifications = verifications;
    }

    public record RequirementRef(String id, String key, String title) {}
    public record Summary(long testCaseCount, int unverifiedCount, int staleCount) {}
    public record VerificationView(String id, int requirementRevision, String testCaseId, String result, String verifiedAt) {}

    private static RequirementRef ref(Requirement r) {
        return new RequirementRef(r.getId().toString(), r.getKey(), r.getTitle());
    }

    /** VYB-0362 AC2: unverified and stale are reported separately, not conflated. */
    @GetMapping("/summary")
    public Summary summary() {
        return new Summary(testCases.count(), requirements.findAllUnverified().size(), requirements.findAllStaleEvidence().size());
    }

    @GetMapping("/unverified")
    public List<RequirementRef> unverified() {
        return requirements.findAllUnverified().stream().map(EvidenceController::ref).toList();
    }

    @GetMapping("/stale")
    public List<RequirementRef> stale() {
        return requirements.findAllStaleEvidence().stream().map(EvidenceController::ref).toList();
    }

    @GetMapping("/requirements/{id}/verifications")
    public List<VerificationView> forRequirement(@PathVariable UUID id) {
        return verifications.findAllByRequirementIdOrderByVerifiedAtDesc(id).stream()
            .map(EvidenceController::toView)
            .toList();
    }

    private static VerificationView toView(Verification v) {
        return new VerificationView(v.getId().toString(), v.getRequirementRevision(),
            v.getTestCaseId() == null ? null : v.getTestCaseId().toString(),
            v.getResult().name(), v.getVerifiedAt().toString());
    }
}
