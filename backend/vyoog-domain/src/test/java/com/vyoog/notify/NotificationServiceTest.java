package com.vyoog.notify;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/** VYB-0356/0357: outbox write alongside the notification, and coalescing repeats of the same kind. */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationRepository notifications;
    @Mock OutboxEventRepository outbox;
    @Mock JdbcTemplate jdbc;
    @Mock NotificationPreferenceService preferences;
    @Mock NotificationDeliveryQueue deliveries;
    @Mock NotifyProperties properties;

    NotificationService service;
    UUID userId;

    @BeforeEach
    void setUp() {
        service = new NotificationService(notifications, outbox, new ObjectMapper(), jdbc, preferences, deliveries, properties);
        // nothing chosen: every kind keeps its default, which for the inbox is on
        lenient().when(preferences.wants(any(), anyString(), eq(NotificationChannel.INBOX))).thenReturn(true);
        userId = UUID.randomUUID();
        lenient().when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(15);
    }

    @Test
    void firstNotificationOfAKindIsANewRow() {
        when(notifications.findFirstByUserIdAndKindAndReadAtIsNullAndCreatedAtAfterOrderByCreatedAtDesc(
            eq(userId), eq("defect-routed"), any())).thenReturn(Optional.empty());

        service.notify(userId, "warn", "defect-routed", "New defect", "detail", "/defects/1");

        verify(notifications).save(argThat(n -> n.getOccurrenceCount() == 1));
        verify(outbox).save(any(OutboxEvent.class));
    }

    /** VYB-0357 AC1: ten within the window produce one item — this is the second one already collapsing. */
    @Test
    void aSecondNotificationOfTheSameKindWithinTheWindowCoalesces() {
        Notification existing = new Notification(userId, "warn", "defect-routed", "New defect", "first", "/defects/1");
        when(notifications.findFirstByUserIdAndKindAndReadAtIsNullAndCreatedAtAfterOrderByCreatedAtDesc(
            eq(userId), eq("defect-routed"), any())).thenReturn(Optional.of(existing));

        service.notify(userId, "warn", "defect-routed", "Another defect", "second", "/defects/2");

        assertThat(existing.getOccurrenceCount()).isEqualTo(2);
        assertThat(existing.getSubtitle()).isEqualTo("second");
        verify(notifications).save(existing);
        verify(outbox).save(any(OutboxEvent.class)); // still published every time, even when coalesced
    }

    @Test
    void aDifferentKindNeverCoalescesWithAnUnrelatedOne() {
        when(notifications.findFirstByUserIdAndKindAndReadAtIsNullAndCreatedAtAfterOrderByCreatedAtDesc(
            eq(userId), eq("clarification-raised"), any())).thenReturn(Optional.empty());

        service.notify(userId, "warn", "clarification-raised", "New clarification", "q", "/requirements/1");

        verify(notifications).save(argThat(n -> n.getKind().equals("clarification-raised") && n.getOccurrenceCount() == 1));
    }

    // ------------------------------------------------------------------ VYB-0955: a person's own choices

    @Test
    void VYB0955_AC2_aKindSwitchedOffForTheInboxLeavesNoInboxRowAndNoLiveEvent() {
        when(preferences.wants(userId, "mentioned", NotificationChannel.INBOX)).thenReturn(false);

        service.notify(userId, "info", "mentioned", "Someone mentioned you", "x", "/requirements/1");

        verify(notifications, never()).save(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void VYB0955_AC3_aKindSwitchedOnForEmailIsQueuedWhenEmailIsSetUpAndUrgentKindsAreQueuedAsUrgent() {
        when(properties.emailEnabled()).thenReturn(true);
        when(preferences.wants(userId, "review-requested", NotificationChannel.EMAIL)).thenReturn(true);

        service.notify(userId, "warn", "review-requested", "Review requested: VY-1", "t", "/requirements/1");

        verify(deliveries).enqueueEmail(eq(userId), any(), eq("review-requested"), eq("Review requested: VY-1"), eq("t"), eq("/requirements/1"), eq(true));
    }

    @Test
    void VYB0955_AC3_aKindThatIsNotUrgentIsQueuedForTheDigest() {
        when(properties.emailEnabled()).thenReturn(true);
        when(preferences.wants(userId, "requirement-approved", NotificationChannel.EMAIL)).thenReturn(true);

        service.notify(userId, "info", "requirement-approved", "VY-1 was approved", "t", "/requirements/1");

        verify(deliveries).enqueueEmail(eq(userId), any(), eq("requirement-approved"), any(), any(), any(), eq(false));
    }

    @Test
    void VYB0955_AC4_nothingIsQueuedForEmailWhenEmailIsNotSetUpOrThePersonDidNotAskForIt() {
        when(properties.emailEnabled()).thenReturn(false);
        service.notify(userId, "warn", "review-requested", "t", "s", "/x");
        verify(deliveries, never()).enqueueEmail(any(), any(), any(), any(), any(), any(), anyBoolean());

        when(properties.emailEnabled()).thenReturn(true);
        when(preferences.wants(userId, "review-requested", NotificationChannel.EMAIL)).thenReturn(false);
        service.notify(userId, "warn", "review-requested", "t", "s", "/x");
        verify(deliveries, never()).enqueueEmail(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void VYB0955_AC4_aKindTheRegistryDoesNotKnowIsNeverEmailedByDefaultSoOldCallersAreUnchanged() {
        assertThat(NotificationKind.defaultEmail("defect-routed")).isFalse();
        assertThat(NotificationKind.defaultInbox("defect-routed")).isTrue();
    }

    @Test
    void routingToAnUnknownActorIsANoOp() {
        service.notify(null, "warn", "defect-routed", "New defect", "detail", "/defects/1");
        verifyNoInteractions(notifications, outbox);
    }
}
