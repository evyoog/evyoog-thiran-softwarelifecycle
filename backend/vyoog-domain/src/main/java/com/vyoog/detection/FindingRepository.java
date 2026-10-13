package com.vyoog.detection;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingRepository extends JpaRepository<Finding, UUID> {
    Optional<Finding> findByFingerprint(String fingerprint);
    List<Finding> findAllByRuleKey(String ruleKey);
    /** VYB-0793: the paged form {@code FindingReconciler} uses for a full rule-wide reconcile, so a large rule's findings never all sit in one Hibernate session at once. */
    Page<Finding> findAllByRuleKey(String ruleKey, Pageable pageable);
    List<Finding> findAllByRuleKeyAndObjectId(String ruleKey, UUID objectId);

    /**
     * VYB-0960: the finding with this fingerprint, if there is one, with its row locked until the transaction ends ({@code SELECT ...
     * FOR UPDATE}): waits for any other transaction that holds it. {@code FindingReconciler.reconcileOne} takes these one at a time in
     * ascending fingerprint order, so that no two writes can wait on each other in a circle.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT f FROM Finding f WHERE f.fingerprint = :fingerprint")
    Optional<Finding> lockByFingerprint(@org.springframework.data.repository.query.Param("fingerprint") String fingerprint);

    Page<Finding> findAllByState(FindingState state, Pageable pageable);
    Page<Finding> findAllByStateAndRuleKey(FindingState state, String ruleKey, Pageable pageable);
    long countByRuleKeyAndState(String ruleKey, FindingState state);
}
