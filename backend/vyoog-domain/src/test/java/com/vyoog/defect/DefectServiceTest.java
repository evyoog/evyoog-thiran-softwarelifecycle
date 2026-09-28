package com.vyoog.defect;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
class DefectServiceTest {

    @Mock DefectRepository defects;
    @Mock DefectKeyAllocator keys;
    @Mock RequirementRepository requirements;
    @Mock AuditService audit;
    @Mock NotificationService notifications;
    @Mock JdbcTemplate jdbc;

    DefectService service;
    UUID actor;

    @BeforeEach
    void setUp() {
        service = new DefectService(defects, keys, requirements, audit, notifications, jdbc);
        actor = UUID.randomUUID();
        lenient().when(defects.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void raisingAgainstARequirementRoutesToItsDeveloperAndTester() {
        UUID reqId = UUID.randomUUID();
        UUID devId = UUID.randomUUID();
        UUID testerId = UUID.randomUUID();
        Requirement r = mock(Requirement.class);
        when(r.getDeveloperId()).thenReturn(devId);
        when(r.getTesterId()).thenReturn(testerId);
        when(requirements.findById(reqId)).thenReturn(Optional.of(r));
        when(keys.next()).thenReturn("DEF-1");

        Defect d = service.raise("Login fails", DefectSeverity.HIGH, reqId, FoundIn.QA, actor);

        assertThat(d.getDeveloperId()).isEqualTo(devId);
        assertThat(d.getTesterId()).isEqualTo(testerId);
        verify(notifications).notify(eq(devId), any(), any(), any(), any(), any());
        verify(notifications).notify(eq(testerId), any(), any(), any(), any(), any());
    }

    @Test
    void raisingUntracedNeverBlocksOnAMissingRequirement() {
        when(keys.next()).thenReturn("DEF-2");

        Defect d = service.raise("Something broke", DefectSeverity.LOW, null, FoundIn.PRODUCTION, actor);

        assertThat(d.isUntraced()).isTrue();
        verifyNoInteractions(notifications);
    }

    @Test
    void closingWithoutARootCauseIsRefused() {
        UUID id = UUID.randomUUID();
        Defect d = new Defect("DEF-3", "Title", DefectSeverity.MEDIUM, null, FoundIn.DEV, null, null);
        when(defects.findById(id)).thenReturn(Optional.of(d));

        assertThatThrownBy(() -> service.close(id, actor)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void classifyingThenClosingSucceeds() {
        UUID id = UUID.randomUUID();
        Defect d = new Defect("DEF-4", "Title", DefectSeverity.MEDIUM, null, FoundIn.DEV, null, null);
        when(defects.findById(id)).thenReturn(Optional.of(d));

        service.classify(id, RootCause.CODING_ERROR, actor);
        Defect closed = service.close(id, actor);

        assertThat(closed.getState()).isEqualTo(DefectState.CLOSED);
    }
}
