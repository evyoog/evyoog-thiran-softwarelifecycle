package com.vyoog.clarification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClarificationRepository extends JpaRepository<Clarification, UUID> {
    List<Clarification> findAllByRequirementIdOrderByRaisedAtAsc(UUID requirementId);
    List<Clarification> findAllByAssignedToAndState(UUID assignedTo, ClarificationState state);
    List<Clarification> findAllByStateAndEscalatedAtIsNullAndRaisedAtBefore(ClarificationState state, Instant cutoff);

    /** VYB-0331/0374: every currently-blocking clarification, for the task derivation. */
    List<Clarification> findAllByStateAndBlocksTaskTrue(ClarificationState state);
}
