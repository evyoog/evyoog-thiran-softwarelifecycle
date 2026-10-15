package com.vyoog.api.scheduling;

import com.vyoog.api.notify.NotificationDeliveryService;
import com.vyoog.api.notify.NotificationRelayService;
import com.vyoog.clarification.ClarificationService;
import com.vyoog.detection.DetectionSweepService;
import com.vyoog.platform.PurgeService;
import com.vyoog.platform.SchedulerLock;
import com.vyoog.platform.audit.AuditRetentionService;
import com.vyoog.release.ReadinessSnapshotService;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * VYB-0909 (F33-F35): every scheduled trigger in the application, in one place, each wrapped in a
 * {@link SchedulerLock} so that with several instances running only one of them does the work.
 *
 * <p>The services keep their own methods (and their own transactions); this class only decides
 * <em>when</em> and <em>on which instance</em>. The services are called through their Spring
 * proxies, so each job's transaction commits before the lease is released.
 *
 * <p>The nightly jobs hold the lease for at least ten minutes ({@code NIGHTLY_MIN_HOLD}) so that a
 * second instance whose cron fires a moment later does not run a short job again. The relay has no
 * minimum, so it is free to run back to back.
 */
@Component
public class ScheduledJobs {

    static final Duration NIGHTLY_MIN_HOLD = Duration.ofMinutes(10);

    private final SchedulerLock locks;
    private final NotificationRelayService relay;
    private final DetectionSweepService sweep;
    private final AuditRetentionService auditRetention;
    private final ClarificationService clarifications;
    private final PurgeService purge;
    private final ReadinessSnapshotService readinessSnapshots;
    private final NotificationDeliveryService deliveries;
    private final com.vyoog.integration.planner.PlannerWebhookDelivery plannerWebhooks;

    public ScheduledJobs(SchedulerLock locks, NotificationRelayService relay, DetectionSweepService sweep,
                         AuditRetentionService auditRetention, ClarificationService clarifications,
                         PurgeService purge, ReadinessSnapshotService readinessSnapshots,
                         NotificationDeliveryService deliveries,
                         com.vyoog.integration.planner.PlannerWebhookDelivery plannerWebhooks) {
        this.locks = locks;
        this.relay = relay;
        this.sweep = sweep;
        this.auditRetention = auditRetention;
        this.clarifications = clarifications;
        this.purge = purge;
        this.readinessSnapshots = readinessSnapshots;
        this.deliveries = deliveries;
        this.plannerWebhooks = plannerWebhooks;
    }

    /** VYB-0791: outbox to live SSE connections, every 2 seconds. */
    @Scheduled(fixedDelay = 2000)
    public void outboxRelay() {
        locks.runExclusive("outbox-relay", Duration.ofSeconds(30), Duration.ZERO, relay::relay);
    }

    /** VYB-0162: 02:00 reconciliation of anything the event path missed. */
    @Scheduled(cron = "0 0 2 * * *")
    public void detectionSweep() {
        locks.runExclusive("detection-sweep", Duration.ofHours(2), NIGHTLY_MIN_HOLD, sweep::nightlySweep);
    }

    /** 02:15 audit partition maintenance. */
    @Scheduled(cron = "0 15 2 * * *")
    public void auditRetention() {
        locks.runExclusive("audit-retention", Duration.ofHours(1), NIGHTLY_MIN_HOLD, auditRetention::scheduledMaintenance);
    }

    /** VYB-0334: 02:30 escalation of ageing clarifications. */
    @Scheduled(cron = "0 30 2 * * *")
    public void clarificationEscalation() {
        locks.runExclusive("clarification-escalation", Duration.ofHours(1), NIGHTLY_MIN_HOLD, clarifications::escalateAgeing);
    }

    /** VYB-0910: 03:00 purge of expired idempotency keys, webhook delivery ids and rate-limit rows. */
    @Scheduled(cron = "0 0 3 * * *")
    public void purgeExpiredRecords() {
        locks.runExclusive("purge-expired-records", Duration.ofHours(1), NIGHTLY_MIN_HOLD, purge::purgeExpired);
    }

    /** VYB-0955: email and chat out of the notification queue, every 30 seconds. */
    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void notificationDelivery() {
        locks.runExclusive("notification-delivery", Duration.ofMinutes(5), Duration.ZERO, deliveries::deliver);
    }

    /** VYB-0918a: signed webhooks to the Agile Planner about the requirements it sent, every 30 seconds. */
    @Scheduled(fixedDelay = 30000, initialDelay = 45000)
    public void plannerWebhooks() {
        locks.runExclusive("planner-webhooks", Duration.ofMinutes(5), Duration.ZERO, plannerWebhooks::deliver);
    }

    /** VYB-0918a: 04:00 removal of sent and failed Planner events older than 30 days. */
    @Scheduled(cron = "0 0 4 * * *")
    public void plannerWebhookPurge() {
        locks.runExclusive("planner-webhooks-purge", Duration.ofHours(1), NIGHTLY_MIN_HOLD, plannerWebhooks::purgeOld);
    }

    /** VYB-0955: 03:45 removal of sent and failed notification deliveries older than 30 days. */
    @Scheduled(cron = "0 45 3 * * *")
    public void notificationDeliveryPurge() {
        locks.runExclusive("notification-delivery-purge", Duration.ofHours(1), NIGHTLY_MIN_HOLD, deliveries::purgeOld);
    }

    /** VYB-0956: 03:15 (UTC) record of each release's readiness for the trend; one row per release per day. */
    @Scheduled(cron = "0 15 3 * * *", zone = "UTC")
    public void readinessSnapshot() {
        locks.runExclusive("readiness-snapshot", Duration.ofHours(1), NIGHTLY_MIN_HOLD,
            () -> readinessSnapshots.takeAll(LocalDate.now(ZoneOffset.UTC)));
    }
}
