package com.vyoog.evidence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestedCommitRepository extends JpaRepository<IngestedCommit, UUID> {

    /** VYB-0316 AC3: re-ingesting the same commit is idempotent. */
    Optional<IngestedCommit> findBySha(String sha);
}
