package com.vyoog.deployment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {
    List<Deployment> findAllByEnvironmentIdOrderByDeployedAtDesc(UUID environmentId);
}
