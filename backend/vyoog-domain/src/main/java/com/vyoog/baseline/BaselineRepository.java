package com.vyoog.baseline;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BaselineRepository extends JpaRepository<Baseline, UUID> {
    List<Baseline> findAllByOrderByFrozenAtDesc();
}
