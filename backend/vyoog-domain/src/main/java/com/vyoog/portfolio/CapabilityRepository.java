package com.vyoog.portfolio;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CapabilityRepository extends JpaRepository<Capability, UUID> {
    List<Capability> findAllByApplicationIdAndArchivedAtIsNull(UUID applicationId);
    long countByApplicationIdAndArchivedAtIsNull(UUID applicationId);
}
