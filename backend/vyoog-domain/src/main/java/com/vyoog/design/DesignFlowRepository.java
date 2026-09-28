package com.vyoog.design;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DesignFlowRepository extends JpaRepository<DesignFlow, UUID> {
    Optional<DesignFlow> findByApplicationId(UUID applicationId);
}
