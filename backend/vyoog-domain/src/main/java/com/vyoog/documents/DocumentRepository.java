package com.vyoog.documents;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, UUID> {
    boolean existsByKey(String key);
    Optional<Document> findByKey(String key);
    List<Document> findAllByProductId(UUID productId);
}
