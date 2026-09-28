package com.vyoog.portfolio;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GlossaryTermUsageRepository extends JpaRepository<GlossaryTermUsage, UUID> {
    List<GlossaryTermUsage> findAllByTermId(UUID termId);
    Optional<GlossaryTermUsage> findByTermIdAndApplicationId(UUID termId, UUID applicationId);
    List<GlossaryTermUsage> findAllByApplicationId(UUID applicationId);
}
