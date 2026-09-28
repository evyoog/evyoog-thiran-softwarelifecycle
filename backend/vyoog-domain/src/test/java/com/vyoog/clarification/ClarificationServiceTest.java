package com.vyoog.clarification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.vyoog.identity.AppUser;
import com.vyoog.identity.AppUserRepository;
import com.vyoog.notify.NotificationService;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class ClarificationServiceTest {

    @Mock ClarificationRepository clarifications;
    @Mock RequirementRepository requirements;
    @Mock AppUserRepository users;
    @Mock NotificationService notifications;
    @Mock AuditService audit;
    @Mock JdbcTemplate jdbc;

    ClarificationService service;
    UUID reqId;
    UUID raiser;

    @BeforeEach
    void setUp() {
        service = new ClarificationService(clarifications, requirements, users, notifications, audit, jdbc);
        reqId = UUID.randomUUID();
        raiser = UUID.randomUUID();
        lenient().when(clarifications.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void blockingDefaultsToTrueWhenTheCallerDoesNotSayOtherwise() {
        Requirement r = mock(Requirement.class);
        when(r.getKey()).thenReturn("VY-1");
        when(requirements.findById(reqId)).thenReturn(Optional.of(r));

        Clarification c = service.raise(reqId, "What does 'promptly' mean?", null, true, raiser);
        assertThat(c.isBlocksTask()).isTrue();
        assertThat(c.getRequirementId()).isEqualTo(reqId); // AC1: always names a requirement
    }

    @Test
    void onlyTheAssigneeOrTheirDelegateMayAnswer() {
        UUID assignee = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        Clarification c = new Clarification(reqId, "Q?", true, raiser, assignee);
        UUID id = UUID.randomUUID();
        when(clarifications.findById(id)).thenReturn(Optional.of(c));
        when(users.findById(assignee)).thenReturn(Optional.empty()); // stranger has no delegate relationship

        assertThatThrownBy(() -> service.answer(id, "It means within 24h", stranger))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theAssigneeThemselvesMayAlwaysAnswer() {
        UUID assignee = UUID.randomUUID();
        Clarification c = new Clarification(reqId, "Q?", true, raiser, assignee);
        UUID id = UUID.randomUUID();
        when(clarifications.findById(id)).thenReturn(Optional.of(c));

        Clarification answered = service.answer(id, "Within 24h", assignee);

        assertThat(answered.getState()).isEqualTo(ClarificationState.ANSWERED);
        verify(notifications).notify(eq(raiser), any(), any(), any(), any(), any());
    }

    @Test
    void aDelegateMayAnswerOnTheAssigneesBehalf() {
        UUID assignee = UUID.randomUUID();
        UUID delegate = UUID.randomUUID();
        Clarification c = new Clarification(reqId, "Q?", true, raiser, assignee);
        UUID id = UUID.randomUUID();
        when(clarifications.findById(id)).thenReturn(Optional.of(c));
        AppUser assigneeUser = mock(AppUser.class);
        when(assigneeUser.getDelegateId()).thenReturn(delegate);
        when(users.findById(assignee)).thenReturn(Optional.of(assigneeUser));

        Clarification answered = service.answer(id, "Within 24h", delegate);

        assertThat(answered.getState()).isEqualTo(ClarificationState.ANSWERED);
    }

    @Test
    void answeringTwiceIsRefused() {
        Clarification c = new Clarification(reqId, "Q?", true, raiser, raiser);
        UUID id = UUID.randomUUID();
        when(clarifications.findById(id)).thenReturn(Optional.of(c));

        service.answer(id, "First answer", raiser);
        assertThatThrownBy(() -> service.answer(id, "Second answer", raiser))
            .isInstanceOf(IllegalStateException.class);
    }
}
