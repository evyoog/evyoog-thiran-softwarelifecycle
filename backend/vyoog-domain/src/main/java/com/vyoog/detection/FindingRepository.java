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
    Page<Finding> findAllByState(FindingState state, Pageable pageable);
    Page<Finding> findAllByStateAndRuleKey(FindingState state, String ruleKey, Pageable pageable);
    long countByRuleKeyAndState(String ruleKey, FindingState state);
}
