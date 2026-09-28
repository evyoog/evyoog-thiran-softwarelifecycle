package com.vyoog.deployment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnvironmentRepository extends JpaRepository<Environment, UUID> {
    List<Environment> findAllByOrderByOrdinalAsc();
}
