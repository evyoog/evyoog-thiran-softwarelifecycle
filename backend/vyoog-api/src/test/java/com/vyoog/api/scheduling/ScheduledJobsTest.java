package com.vyoog.api.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vyoog.api.notify.NotificationRelayService;
import com.vyoog.clarification.ClarificationService;
import com.vyoog.detection.DetectionSweepService;
import com.vyoog.platform.PurgeService;
import com.vyoog.platform.SchedulerLock;
import com.vyoog.platform.audit.AuditRetentionService;
import java.lang.reflect.Method;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/** VYB-0909 (F33-F35): each trigger asks for its lease first, under a stable name, and runs the right service. */
class ScheduledJobsTest {

    SchedulerLock locks = mock(SchedulerLock.class);
    NotificationRelayService relay = mock(NotificationRelayService.class);
    DetectionSweepService sweep = mock(DetectionSweepService.class);
    AuditRetentionService audit = mock(AuditRetentionService.class);
    ClarificationService clarifications = mock(ClarificationService.class);
    PurgeService purge = mock(PurgeService.class);
    ScheduledJobs jobs;

    @BeforeEach
    void setUp() {
        // A lock that grants the lease and runs the job, as the winning instance would.
        when(locks.runExclusive(any(), any(), any(), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(3)).run();
            return true;
        });
        jobs = new ScheduledJobs(locks, relay, sweep, audit, clarifications, purge);
    }

    @Test
    void VYB0909_AC3_theOutboxRelayRunsUnderItsOwnLeaseWithNoMinimumHold() {
        jobs.outboxRelay();
        verify(locks).runExclusive(eq("outbox-relay"), any(Duration.class), eq(Duration.ZERO), any());
        verify(relay).relay();
    }

    @Test
    void VYB0909_AC3_theNightlySweepRunsUnderItsOwnLeaseWithAMinimumHold() {
        jobs.detectionSweep();
        verify(locks).runExclusive(eq("detection-sweep"), any(Duration.class), eq(ScheduledJobs.NIGHTLY_MIN_HOLD), any());
        verify(sweep).nightlySweep();
    }

    @Test
    void VYB0909_AC3_auditMaintenanceAndClarificationEscalationEachRunUnderTheirOwnLease() {
        jobs.auditRetention();
        jobs.clarificationEscalation();
        verify(locks).runExclusive(eq("audit-retention"), any(Duration.class), eq(ScheduledJobs.NIGHTLY_MIN_HOLD), any());
        verify(locks).runExclusive(eq("clarification-escalation"), any(Duration.class), eq(ScheduledJobs.NIGHTLY_MIN_HOLD), any());
        verify(audit).scheduledMaintenance();
        verify(clarifications).escalateAgeing();
    }

    @Test
    void VYB0910_AC3_theNightlyPurgeRunsUnderItsOwnLease() {
        jobs.purgeExpiredRecords();
        verify(locks).runExclusive(eq("purge-expired-records"), any(Duration.class), eq(ScheduledJobs.NIGHTLY_MIN_HOLD), any());
        verify(purge).purgeExpired();
    }

    @Test
    void VYB0909_AC3_anInstanceThatDoesNotWinTheLeaseRunsNothing() {
        org.mockito.Mockito.doReturn(false).when(locks).runExclusive(any(), any(), any(), any());
        jobs.outboxRelay();
        jobs.detectionSweep();
        jobs.auditRetention();
        jobs.clarificationEscalation();
        jobs.purgeExpiredRecords();
        org.mockito.Mockito.verifyNoInteractions(relay, sweep, audit, clarifications, purge);
    }

    @Test
    void VYB0909_AC3_theSchedulesAreTheOnesTheServicesHadBefore() throws Exception {
        assertThat(scheduled("outboxRelay").fixedDelay()).isEqualTo(2000);
        assertThat(scheduled("detectionSweep").cron()).isEqualTo("0 0 2 * * *");
        assertThat(scheduled("auditRetention").cron()).isEqualTo("0 15 2 * * *");
        assertThat(scheduled("clarificationEscalation").cron()).isEqualTo("0 30 2 * * *");
        assertThat(scheduled("purgeExpiredRecords").cron()).isEqualTo("0 0 3 * * *");
    }

    private static Scheduled scheduled(String method) throws Exception {
        Method m = ScheduledJobs.class.getMethod(method);
        return m.getAnnotation(Scheduled.class);
    }
}
