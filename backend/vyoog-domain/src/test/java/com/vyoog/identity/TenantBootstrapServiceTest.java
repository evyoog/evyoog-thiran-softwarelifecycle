package com.vyoog.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vyoog.platform.audit.AuditService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** VYB-0901 (F04): bootstrap is closed once it has run, and also whenever an administrator already exists. */
class TenantBootstrapServiceTest {

    private final AccessGrantService grants = mock(AccessGrantService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TenantBootstrapService service = new TenantBootstrapService(grants, jdbc, mock(AuditService.class));

    private void state(boolean bootstrapped, boolean adminExists) {
        when(jdbc.queryForObject(contains("bootstrapped_at IS NOT NULL"), eq(Boolean.class))).thenReturn(bootstrapped);
        when(jdbc.queryForObject(contains("FROM access_grant"), eq(Boolean.class))).thenReturn(adminExists);
    }

    @Test
    void VYB0901_AC1_refusedWhenAnAdministratorAlreadyExistsEvenIfNeverBootstrapped() {
        state(false, true);
        assertThatThrownBy(() -> service.bootstrap(UUID.randomUUID(), UUID.randomUUID()))
            .isInstanceOf(BootstrapRefusedException.class).hasMessageContaining("administrator already exists");
        verify(grants, never()).grant(any(), any(), any(), any(), any(), any());
    }

    @Test
    void VYB0901_AC1_refusedWhenAlreadyBootstrapped() {
        state(true, false);
        assertThatThrownBy(() -> service.bootstrap(UUID.randomUUID(), UUID.randomUUID()))
            .isInstanceOf(BootstrapRefusedException.class).hasMessageContaining("already bootstrapped");
        verify(grants, never()).grant(any(), any(), any(), any(), any(), any());
    }

    @Test
    void VYB0901_AC1_grantsTheFirstAdministratorOnlyWhenNeitherBlocks() {
        state(false, false);
        UUID first = UUID.randomUUID();
        service.bootstrap(first, UUID.randomUUID());
        verify(grants).grant(eq(first), eq(AccessRole.ADMINISTRATOR), eq(ScopeType.PLATFORM), any(), any(), any());
        verify(jdbc).update(anyString(), any(java.sql.Timestamp.class));
    }
}
