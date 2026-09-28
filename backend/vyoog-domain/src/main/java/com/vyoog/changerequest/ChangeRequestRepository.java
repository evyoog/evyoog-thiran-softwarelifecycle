package com.vyoog.changerequest;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChangeRequestRepository extends JpaRepository<ChangeRequest, UUID> {
}
