package com.vyoog.release;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScopeMovementRepository extends JpaRepository<ScopeMovement, UUID> {
    /** VYB-0475 AC1/VYB-0516 AC1: queryable over a selectable window. */
    List<ScopeMovement> findAllByReleaseIdAndMovedAtBetweenOrderByMovedAtDesc(
        UUID releaseId, Instant from, Instant to);
}
