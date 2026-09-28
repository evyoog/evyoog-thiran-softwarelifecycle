package com.vyoog.design;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DesignEdgeRepository extends JpaRepository<DesignEdge, UUID> {
    List<DesignEdge> findAllByFlowId(UUID flowId);
}
