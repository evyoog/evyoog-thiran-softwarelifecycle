package com.vyoog.requirements;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequirementRevisionRepository extends JpaRepository<RequirementRevision, UUID> {
    List<RequirementRevision> findAllByRequirementIdOrderByRevisionAsc(UUID requirementId);
}
