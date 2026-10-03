package com.vyoog.platform;

import com.vyoog.platform.audit.AuditService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0910 (F36): the tables that only ever grew. Three small bookkeeping tables hold a row per
 * request or delivery and nothing ever deleted one:
 *
 * <ul>
 *   <li>{@code idempotency_key} (V004): a repeated create with the same key returns the first
 *       response. Kept {@code vyoog.retention.idempotency-days} (default 7). A client that retries
 *       the same key after that creates a second row instead of getting the first response.
 *   <li>{@code webhook_delivery} (V007): the replay protection for inbound webhooks, the only one
 *       there is (the signed payload carries no timestamp). Kept
 *       {@code vyoog.retention.webhook-delivery-days} (default 90). After that, a captured and
 *       correctly signed delivery with an old id would be accepted again, so this is a security
 *       setting as much as a storage one. Both defaults were set by the product owner.
 *   <li>{@code connector_sync_log} (V039, VYB-0913): one row per outbound connector operation. Kept
 *       {@code vyoog.retention.connector-sync-log-days} (default 90, an operational default chosen
 *       with the log, not a decision recorded elsewhere). It holds no payload and no secret.
 *   <li>{@code rate_limit_hit} (V035): pruned by {@link RateLimiter#prune()}, which is run here too
 *       so that it does not depend on the occasional prune that rides on a successful call.
 * </ul>
 *
 * <p>Rows go in batches, each its own transaction, so a large backlog never holds one long lock or
 * one long transaction. Age is measured by the database clock. Non-positive retention is refused at
 * startup: a 0 would delete everything. When anything was purged, one {@code retention.purged}
 * system audit event records how many of each.
 *
 * <p>Not transactional on purpose: each batch commits by itself. Triggered nightly from
 * {@code ScheduledJobs} under a {@link SchedulerLock}.
 */
@Service
public class PurgeService {

    private static final Logger log = LoggerFactory.getLogger(PurgeService.class);
    static final int BATCH_SIZE = 5000;

    private final JdbcTemplate jdbc;
    private final RateLimiter rateLimiter;
    private final AuditService audit;
    private final MeterRegistry meters;
    private final int idempotencyDays;
    private final int webhookDeliveryDays;
    private final int connectorSyncLogDays;

    public PurgeService(JdbcTemplate jdbc, RateLimiter rateLimiter, AuditService audit, MeterRegistry meters,
                        @Value("${vyoog.retention.idempotency-days:7}") int idempotencyDays,
                        @Value("${vyoog.retention.webhook-delivery-days:90}") int webhookDeliveryDays,
                        @Value("${vyoog.retention.connector-sync-log-days:90}") int connectorSyncLogDays) {
        if (idempotencyDays < 1) {
            throw new IllegalArgumentException("vyoog.retention.idempotency-days must be at least 1, got " + idempotencyDays);
        }
        if (webhookDeliveryDays < 1) {
            throw new IllegalArgumentException("vyoog.retention.webhook-delivery-days must be at least 1, got " + webhookDeliveryDays);
        }
        if (connectorSyncLogDays < 1) {
            throw new IllegalArgumentException("vyoog.retention.connector-sync-log-days must be at least 1, got " + connectorSyncLogDays);
        }
        this.jdbc = jdbc;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.meters = meters;
        this.idempotencyDays = idempotencyDays;
        this.webhookDeliveryDays = webhookDeliveryDays;
        this.connectorSyncLogDays = connectorSyncLogDays;
    }

    /** @return rows deleted, by table. */
    public Map<String, Integer> purgeExpired() {
        Map<String, Integer> deleted = new LinkedHashMap<>();
        deleted.put("idempotency_key", purgeIdempotencyKeys());
        deleted.put("webhook_delivery", purgeWebhookDeliveries());
        deleted.put("connector_sync_log", purgeConnectorSyncLog());
        deleted.put("rate_limit_hit", rateLimiter.prune());

        int total = deleted.values().stream().mapToInt(Integer::intValue).sum();
        deleted.forEach((table, n) -> meters.counter("vyoog.purge.deleted", "table", table).increment(n));
        if (total > 0) {
            log.info("[purge] deleted {}", deleted);
            audit.recordSystem("retention.purged", "RETENTION", null, Map.copyOf(deleted));
        }
        return deleted;
    }

    public int purgeIdempotencyKeys() {
        return deleteOlderThan("idempotency_key", "created_at", idempotencyDays);
    }

    public int purgeWebhookDeliveries() {
        return deleteOlderThan("webhook_delivery", "received_at", webhookDeliveryDays);
    }

    public int purgeConnectorSyncLog() {
        return deleteOlderThan("connector_sync_log", "started_at", connectorSyncLogDays);
    }

    /** Table and column are constants of this class, never input, so they are safe to concatenate. */
    private int deleteOlderThan(String table, String column, int days) {
        int total = 0;
        int batch;
        do {
            batch = jdbc.update(
                "DELETE FROM " + table + " WHERE ctid IN (SELECT ctid FROM " + table + " WHERE " + column
                    + " < clock_timestamp() - make_interval(days => ?) LIMIT ?)",
                days, BATCH_SIZE);
            total += batch;
        } while (batch == BATCH_SIZE);
        return total;
    }
}
