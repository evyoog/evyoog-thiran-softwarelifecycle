package com.vyoog.requirements;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AcceptanceCriterionRepository extends JpaRepository<AcceptanceCriterion, UUID> {
    List<AcceptanceCriterion> findAllByRequirementIdOrderByOrdinalAsc(UUID requirementId);
    long countByRequirementId(UUID requirementId);
    void deleteAllByRequirementId(UUID requirementId);

    /** VYB-0666: counts for a whole page in one query, rather than one per row. */
    @org.springframework.data.jpa.repository.Query(
        "SELECT c.requirementId, COUNT(c) FROM AcceptanceCriterion c WHERE c.requirementId IN :ids GROUP BY c.requirementId")
    List<Object[]> countsByRequirementIds(@org.springframework.data.repository.query.Param("ids") List<UUID> ids);
}
