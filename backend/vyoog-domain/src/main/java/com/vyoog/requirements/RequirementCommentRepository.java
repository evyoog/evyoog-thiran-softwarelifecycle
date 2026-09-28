package com.vyoog.requirements;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequirementCommentRepository extends JpaRepository<RequirementComment, UUID> {
    List<RequirementComment> findAllByRequirementIdOrderByCreatedAtAsc(UUID requirementId);
}
