package com.vyoog.trace;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TraceLinkRepository extends JpaRepository<TraceLink, UUID> {
    boolean existsByFromTypeAndFromIdAndToTypeAndToIdAndLinkType(
        TraceObjectType fromType, UUID fromId, TraceObjectType toType, UUID toId, TraceLinkType linkType);

    /** The links this object is the source of — for showing/managing them, not traversal. */
    List<TraceLink> findAllByFromTypeAndFromId(TraceObjectType fromType, UUID fromId);

    /** The links this object is the target of. */
    List<TraceLink> findAllByToTypeAndToId(TraceObjectType toType, UUID toId);
}
