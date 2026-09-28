package com.vyoog.portfolio;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<Application, UUID> {
    List<Application> findAllByProductIdAndArchivedAtIsNull(UUID productId);
    long countByProductIdAndArchivedAtIsNull(UUID productId);
}
