package com.vyoog.integration;

import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0740–0743. Verification and replay-protection are exercised by {@link
 * WebhookSignatureVerifier} and {@link WebhookDeliveryRepository} directly — this
 * class is the connection registry plus the one thing both need: recording a
 * verified, non-replayed delivery's outcome against the connection it came in on.
 */
@Service
public class IntegrationService {

    private final IntegrationConnectionRepository connections;
    private final WebhookDeliveryRepository deliveries;
    private final JdbcTemplate jdbc;

    public IntegrationService(IntegrationConnectionRepository connections,
                               WebhookDeliveryRepository deliveries, JdbcTemplate jdbc) {
        this.connections = connections;
        this.deliveries = deliveries;
        this.jdbc = jdbc;
    }

    public List<IntegrationConnection> all() {
        return connections.findAll();
    }

    public IntegrationConnection get(String key) {
        return connections.findById(key).orElseThrow(NoSuchElementException::new);
    }

    /**
     * VYB-0741: verifies the signature (AC1), then records the delivery (AC2) — if
     * this returns false, the delivery id was already seen and nothing should
     * reprocess; the caller still answers 200, exactly what "ignored idempotently"
     * means, as opposed to refusing it as an error.
     */
    @Transactional
    public boolean acceptDelivery(String integrationKey, String deliveryId, String rawBody, String presentedSignature) {
        IntegrationConnection conn = get(integrationKey);
        if (conn.getWebhookSecret() == null) {
            throw new IllegalStateException("No webhook secret configured for " + integrationKey);
        }
        if (!WebhookSignatureVerifier.verify(conn.getWebhookSecret(), rawBody, presentedSignature)) {
            throw new InvalidWebhookSignatureException("Signature did not verify for " + integrationKey);
        }
        try {
            deliveries.save(new WebhookDelivery(integrationKey, deliveryId));
        } catch (DataIntegrityViolationException replay) {
            return false; // VYB-0741 AC2: already seen — ignored, not reprocessed
        }
        conn.recordSuccess();
        connections.save(conn);
        return true;
    }

    /** VYB-0743: a failure is recorded and surfaced; after DEGRADE_AFTER_FAILURES it's marked degraded rather than retried silently forever. */
    @Transactional
    public void recordFailure(String integrationKey, String error) {
        IntegrationConnection conn = get(integrationKey);
        conn.recordFailure(error);
        connections.save(conn);
    }

    @Transactional
    public IntegrationConnection setConnected(String key, boolean connected, String webhookSecret) {
        IntegrationConnection conn = get(key);
        conn.setConnected(connected);
        if (webhookSecret != null) conn.setWebhookSecret(webhookSecret);
        return connections.save(conn);
    }

    /** VYB-0465/0507 (session 16): the one setter {@code config} never had. */
    @Transactional
    public IntegrationConnection setConfig(String key, String configJson) {
        IntegrationConnection conn = get(key);
        conn.setConfig(configJson);
        return connections.save(conn);
    }

    /**
     * VYB-0839: registers a new connection beyond V007's four seeded ones — Vyoog can
     * now be told a new external system exists, though nothing actually pushes to or
     * pulls from it until real code (like {@link com.vyoog.brief.BriefPushService} for
     * "planning") is written against its key, same as every existing connection.
     */
    @Transactional
    public IntegrationConnection create(String key, String owns, IntegrationConnection.Direction direction) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A connection needs a key.");
        }
        if (connections.existsById(key)) {
            throw new IllegalStateException("A connection with key \"" + key + "\" already exists.");
        }
        return connections.save(new IntegrationConnection(key, owns, direction));
    }

    /** VYB-0839: {@code owns}/{@code direction} were set once at seed time (V007) and never editable since. */
    @Transactional
    public IntegrationConnection updateDetails(String key, String owns, IntegrationConnection.Direction direction) {
        IntegrationConnection conn = get(key);
        if (owns != null) conn.setOwns(owns);
        if (direction != null) conn.setDirection(direction);
        return connections.save(conn);
    }
}
