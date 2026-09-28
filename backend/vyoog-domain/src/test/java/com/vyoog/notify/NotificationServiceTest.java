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

    NotificationService service;
    UUID userId;

    @BeforeEach
    void setUp() {
        service = new NotificationService(notifications, outbox, new ObjectMapper(), jdbc);
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

    @Test
    void routingToAnUnknownActorIsANoOp() {
        service.notify(null, "warn", "defect-routed", "New defect", "detail", "/defects/1");
        verifyNoInteractions(notifications, outbox);
    }
}
