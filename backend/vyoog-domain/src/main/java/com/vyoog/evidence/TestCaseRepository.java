package com.vyoog.evidence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestCaseRepository extends JpaRepository<TestCase, UUID> {
    Optional<TestCase> findByKey(String key);
}
