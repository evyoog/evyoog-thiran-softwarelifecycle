package com.vyoog.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceAccountRepository extends JpaRepository<ServiceAccount, UUID> {

    boolean existsByClientId(String clientId);

    Optional<ServiceAccount> findByClientId(String clientId);

    /** VYB-0712 AC1: recognise a caller still using the previous key during the overlap window. */
    List<ServiceAccount> findAllByPreviousClientId(String previousClientId);

    boolean existsByName(String name);
}
