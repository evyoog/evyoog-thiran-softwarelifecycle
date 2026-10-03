package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vyoog.platform.PurgeService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * VYB-0910 (F36): the purge jobs for the tables that only ever grew. Retention in these tests is
 * the configured default (idempotency 7 days, webhook deliveries 90), set by the product owner.
 */
class PurgeServiceIT extends IntegrationTestBase {

    @Autowired PurgeService purge;

    private void idempotencyKey(String endpoint, String key, int ageDays) {
        jdbc.update("INSERT INTO idempotency_key (key, endpoint, response_id, created_at) "
            + "VALUES (?, ?, gen_random_uuid(), clock_timestamp() - make_interval(days => ?))", key, endpoint, ageDays);
    }

    private void webhookDelivery(String deliveryId, int ageDays) {
        jdbc.update("INSERT INTO webhook_delivery (integration_key, delivery_id, received_at) "
            + "VALUES ('git', ?, clock_timestamp() - make_interval(days => ?))", deliveryId, ageDays);
    }

    private boolean idempotencyKeyExists(String endpoint, String key) {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_key WHERE endpoint = ? AND key = ?",
            Integer.class, endpoint, key) == 1;
    }

    private boolean webhookDeliveryExists(String deliveryId) {
        return jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE delivery_id = ?",
            Integer.class, deliveryId) == 1;
    }

    @Test
    void VYB0910_AC2_anIdempotencyKeyOlderThanSevenDaysIsDeletedAndANewerOneIsKept() {
        String endpoint = unique("ep");
        idempotencyKey(endpoint, "old", 8);
        idempotencyKey(endpoint, "recent", 6);
        idempotencyKey(endpoint, "today", 0);

        purge.purgeIdempotencyKeys();

        assertThat(idempotencyKeyExists(endpoint, "old")).isFalse();
        assertThat(idempotencyKeyExists(endpoint, "recent")).isTrue();
        assertThat(idempotencyKeyExists(endpoint, "today")).isTrue();
    }

    @Test
    void VYB0910_AC2_aWebhookDeliveryIdOlderThanNinetyDaysIsDeletedAndANewerOneIsKept() {
        String tag = unique("dlv");
        webhookDelivery(tag + "-old", 91);
        webhookDelivery(tag + "-recent", 89);

        purge.purgeWebhookDeliveries();

        assertThat(webhookDeliveryExists(tag + "-old")).isFalse();
        assertThat(webhookDeliveryExists(tag + "-recent")).isTrue();
    }

    @Test
    void VYB0913_AC4_aConnectorSyncLogRowOlderThanNinetyDaysIsDeletedAndANewerOneIsKept() {
        String connection = unique("pc");
        jdbc.update("INSERT INTO integration_connection (key, connected) VALUES (?, false)", connection);
        jdbc.update("""
            INSERT INTO connector_sync_log (connection_key, operation, idempotency_key, status, payload_bytes, payload_sha256, started_at)
            VALUES (?, 'x.y', 'old', 'FAILED', 0, 'x', clock_timestamp() - interval '91 days'),
                   (?, 'x.y', 'recent', 'SUCCEEDED', 0, 'x', clock_timestamp() - interval '89 days')""", connection, connection);

        purge.purgeConnectorSyncLog();

        assertThat(jdbc.queryForList("SELECT idempotency_key FROM connector_sync_log WHERE connection_key = ?", String.class, connection))
            .containsExactly("recent");
    }

    @Test
    void VYB0910_AC2_aKeptDeliveryIdStillRefusesAReplay() {
        // The purpose of the table: a delivery id seen once is refused the second time.
        String id = unique("dlv");
        webhookDelivery(id, 30);
        assertThat(jdbc.update("INSERT INTO webhook_delivery (integration_key, delivery_id) VALUES ('git', ?) "
            + "ON CONFLICT DO NOTHING", id)).as("a replay inside the retention window is still a duplicate").isZero();
        purge.purgeWebhookDeliveries();
        assertThat(webhookDeliveryExists(id)).isTrue();
    }

    @Test
    void VYB0910_AC2_aBacklogLargerThanOneBatchIsDeletedCompletely() {
        String endpoint = unique("bulk");
        int rows = 12_000; // more than two batches of 5,000
        jdbc.update("INSERT INTO idempotency_key (key, endpoint, response_id, created_at) "
            + "SELECT 'k' || g, ?, gen_random_uuid(), clock_timestamp() - interval '30 days' FROM generate_series(1, ?) g",
            endpoint, rows);
        jdbc.update("INSERT INTO idempotency_key (key, endpoint, response_id) VALUES ('fresh', ?, gen_random_uuid())", endpoint);

        int deleted = purge.purgeIdempotencyKeys();

        assertThat(deleted).isGreaterThanOrEqualTo(rows);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_key WHERE endpoint = ?", Integer.class, endpoint))
            .as("only the fresh row is left").isEqualTo(1);
    }

    @Test
    void VYB0910_AC2_theNightlyPurgeAlsoPrunesTheRateLimiterAndReportsWhatItDeleted() {
        String key = unique("rl");
        jdbc.update("INSERT INTO rate_limit_hit (key, last_call) VALUES (?, clock_timestamp() - interval '3 hours')", key);
        String endpoint = unique("ep");
        idempotencyKey(endpoint, "old", 20);

        Map<String, Integer> deleted = purge.purgeExpired();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM rate_limit_hit WHERE key = ?", Integer.class, key)).isZero();
        assertThat(deleted).containsKeys("idempotency_key", "webhook_delivery", "connector_sync_log", "rate_limit_hit");
        assertThat(deleted.get("idempotency_key")).isGreaterThanOrEqualTo(1);
        assertThat(deleted.get("rate_limit_hit")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void VYB0910_AC2_aPurgeThatDeletedSomethingLeavesOneSystemAuditEvent() {
        int before = jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'retention.purged'", Integer.class);
        idempotencyKey(unique("ep"), "old", 20);

        purge.purgeExpired();

        int after = jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'retention.purged'", Integer.class);
        assertThat(after).isGreaterThan(before);
        assertThat(jdbc.queryForObject(
            "SELECT actor_type FROM audit_event WHERE action = 'retention.purged' ORDER BY occurred_at DESC LIMIT 1",
            String.class)).isEqualTo("SYSTEM");
    }

    @Test
    void VYB0910_AC2_aRetentionOfZeroOrLessIsRefusedBecauseItWouldDeleteEverything() {
        assertThatThrownBy(() -> new PurgeService(jdbc, null, null, new SimpleMeterRegistry(), 7, 90, 0))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("connector-sync-log-days");
        assertThatThrownBy(() -> new PurgeService(jdbc, null, null, new SimpleMeterRegistry(), 0, 90, 90))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("idempotency-days");
        assertThatThrownBy(() -> new PurgeService(jdbc, null, null, new SimpleMeterRegistry(), 7, -1, 90))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("webhook-delivery-days");
    }
}
