package com.vyoog.design;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DesignNodeRepository extends JpaRepository<DesignNode, UUID> {
    List<DesignNode> findAllByFlowId(UUID flowId);
}
