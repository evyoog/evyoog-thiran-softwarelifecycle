package com.vyoog.portfolio;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GlossaryTermRepository extends JpaRepository<GlossaryTerm, UUID> {
    List<GlossaryTerm> findAllByOrderByTermAsc();
    Optional<GlossaryTerm> findByTerm(String term);
}
