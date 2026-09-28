package com.vyoog.defect;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DefectRepository extends JpaRepository<Defect, UUID> {
    Page<Defect> findAllByState(DefectState state, Pageable pageable);
    List<Defect> findAllByRequirementId(UUID requirementId);
}
