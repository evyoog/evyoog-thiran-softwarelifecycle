package com.vyoog.integration;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {
    boolean existsByIntegrationKeyAndDeliveryId(String integrationKey, String deliveryId);
}
