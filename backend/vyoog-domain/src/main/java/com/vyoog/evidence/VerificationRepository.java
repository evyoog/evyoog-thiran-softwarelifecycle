package com.vyoog.evidence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationRepository extends JpaRepository<Verification, UUID> {

    /** VYB-0311 AC3: re-ingesting the same run's same result for the same pair is a no-op. */
    boolean existsByTestRunIdAndTestCaseIdAndRequirementId(UUID testRunId, UUID testCaseId, UUID requirementId);

    List<Verification> findAllByRequirementIdOrderByVerifiedAtDesc(UUID requirementId);
}
