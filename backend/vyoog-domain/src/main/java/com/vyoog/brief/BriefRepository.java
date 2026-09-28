package com.vyoog.brief;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BriefRepository extends JpaRepository<Brief, UUID> {
    List<Brief> findAllByApplicationIdOrderByGeneratedAtDesc(UUID applicationId);

    /** VYB-0836: the Delivery screen's history, before any product/application is picked. */
    List<Brief> findAllByOrderByGeneratedAtDesc();
}
