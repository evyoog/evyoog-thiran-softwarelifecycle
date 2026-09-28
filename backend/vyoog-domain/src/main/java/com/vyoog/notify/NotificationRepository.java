package com.vyoog.notify;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    List<Notification> findAllByUserIdOrderByCreatedAtDesc(UUID userId);
    long countByUserIdAndReadAtIsNull(UUID userId);

    /** VYB-0357: the open item this next one of the same kind would coalesce into, if any. */
    Optional<Notification> findFirstByUserIdAndKindAndReadAtIsNullAndCreatedAtAfterOrderByCreatedAtDesc(
        UUID userId, String kind, Instant after);
}
