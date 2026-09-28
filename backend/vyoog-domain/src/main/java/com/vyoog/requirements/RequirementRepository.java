package com.vyoog.requirements;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface RequirementRepository
        extends JpaRepository<Requirement, UUID>, JpaSpecificationExecutor<Requirement> {
    Optional<Requirement> findByKey(String key);
    long countByStatus(RequirementStatus status);
    List<Requirement> findAllByCapabilityIdInAndDeletedAtIsNull(List<UUID> capabilityIds);

    // VYB-0135: trigram similarity — see RequirementSimilarityRepository, which needs
    // raw SQL (pg_trgm's similarity() function has no JPQL equivalent).

    /** VYB-0314: reuses the view V001__baseline.sql already defines for exactly this. */
    @Query(value = """
        SELECT r.* FROM requirement r
        JOIN requirement_verification_state vs ON vs.id = r.id
        WHERE vs.has_stale_evidence = true AND r.deleted_at IS NULL
        ORDER BY r.key
        """, nativeQuery = true)
    List<Requirement> findAllStaleEvidence();

    /** VYB-0362 AC1: "unverified" = approved with no pass at the current revision. */
    @Query(value = """
        SELECT r.* FROM requirement r
        JOIN requirement_verification_state vs ON vs.id = r.id
        WHERE r.status = 'APPROVED' AND NOT vs.is_verified AND r.deleted_at IS NULL
        ORDER BY r.key
        """, nativeQuery = true)
    List<Requirement> findAllUnverified();
}
