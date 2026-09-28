package com.vyoog.notify;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    /** VYB-0791: what the relay polls — oldest first, bounded, so one huge backlog can't starve the loop. */
    List<OutboxEvent> findAllByPublishedAtIsNullOrderByIdAsc(Pageable pageable);
}
