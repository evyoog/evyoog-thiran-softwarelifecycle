package com.vyoog.evidence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestRunRepository extends JpaRepository<TestRun, UUID> {

    /** VYB-0311 AC3: the natural idempotency key for "the same CI run" is its build+source. */
    Optional<TestRun> findByBuildLabelAndSource(String buildLabel, String source);
}
