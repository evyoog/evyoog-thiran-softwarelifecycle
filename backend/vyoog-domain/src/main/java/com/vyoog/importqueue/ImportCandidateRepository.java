package com.vyoog.importqueue;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportCandidateRepository extends JpaRepository<ImportCandidate, UUID> {
    List<ImportCandidate> findAllByBatchId(UUID batchId);
}
