package com.vyoog.importqueue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentAnalysisRepository extends JpaRepository<DocumentAnalysis, UUID> {

    /** The current proposal for a batch: the most recent run, whatever its state. */
    Optional<DocumentAnalysis> findFirstByBatchIdOrderByCreatedAtDesc(UUID batchId);

    /** Earlier runs are kept, not overwritten — see {@link DocumentAnalysis}. */
    List<DocumentAnalysis> findAllByBatchIdOrderByCreatedAtDesc(UUID batchId);
}
