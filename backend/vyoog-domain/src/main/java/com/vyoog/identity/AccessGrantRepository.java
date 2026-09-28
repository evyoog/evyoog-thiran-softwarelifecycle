package com.vyoog.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessGrantRepository extends JpaRepository<AccessGrant, UUID> {

    List<AccessGrant> findAllByUserId(UUID userId);
}
