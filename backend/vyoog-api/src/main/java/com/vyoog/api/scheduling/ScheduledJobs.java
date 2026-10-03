package com.vyoog.api.scheduling;

import com.vyoog.api.notify.NotificationRelayService;
import com.vyoog.clarification.ClarificationService;
import com.vyoog.detection.DetectionSweepService;
import com.vyoog.platform.SchedulerLock;
import com.vyoog.platform.audit.AuditRetentionService;
import java.time.Duration;
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

    public ScheduledJobs(SchedulerLock locks, NotificationRelayService relay, DetectionSweepService sweep,
                         AuditRetentionService auditRetention, ClarificationService clarifications) {
        this.locks = locks;
        this.relay = relay;
        this.sweep = sweep;
        this.auditRetention = auditRetention;
        this.clarifications = clarifications;
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
}
