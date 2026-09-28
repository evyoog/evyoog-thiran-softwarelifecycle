package com.vyoog.integration;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** VYB-0741 AC2: one row per delivery ever accepted — a second delivery with the same id is a replay, refused idempotently. */
@Entity
@Table(name = "webhook_delivery")
public class WebhookDelivery {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "integration_key", nullable = false)
    private String integrationKey;

    @Column(name = "delivery_id", nullable = false)
    private String deliveryId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    protected WebhookDelivery() {}

    public WebhookDelivery(String integrationKey, String deliveryId) {
        this.integrationKey = integrationKey;
        this.deliveryId = deliveryId;
    }

    public UUID getId() { return id; }
}
