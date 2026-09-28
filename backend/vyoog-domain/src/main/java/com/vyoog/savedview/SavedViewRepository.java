package com.vyoog.savedview;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedViewRepository extends JpaRepository<SavedView, UUID> {
    List<SavedView> findAllByOwnerIdOrderByNameAsc(UUID ownerId);
    Optional<SavedView> findByOwnerIdAndNameIgnoreCase(UUID ownerId, String name);
}
