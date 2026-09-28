package com.vyoog.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * VYB-0839: "Connected systems" was fixed to V007's four seeded rows (git/ci/hr/planning)
 * with no create path at all — {@link IntegrationService#get} throws for any unknown key.
 * These cover the new {@code create}/{@code updateDetails} that let a new connection be
 * registered, and let {@code owns}/{@code direction} be edited on any existing one — a row
 * only, same as every seeded connection: nothing pushes to or pulls from it until real code
 * is written against its key.
 */
@ExtendWith(MockitoExtension.class)
class IntegrationServiceTest {

    @Mock IntegrationConnectionRepository connections;
    @Mock WebhookDeliveryRepository deliveries;
    @Mock JdbcTemplate jdbc;

    IntegrationService service;

    @BeforeEach
    void setUp() {
        service = new IntegrationService(connections, deliveries, jdbc);
    }

    @Test
    void VYB0839_AC1_createsANewConnectionWithTheGivenKeyOwnsAndDirection() {
        when(connections.existsById("delivery-tool-2")).thenReturn(false);
        when(connections.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var conn = service.create("delivery-tool-2", "second delivery-tool push", IntegrationConnection.Direction.OUTBOUND);

        assertThat(conn.getKey()).isEqualTo("delivery-tool-2");
        assertThat(conn.getOwns()).isEqualTo("second delivery-tool push");
        assertThat(conn.getDirection()).isEqualTo(IntegrationConnection.Direction.OUTBOUND);
        assertThat(conn.isConnected()).isFalse();
    }

    @Test
    void VYB0839_AC2_refusesADuplicateKeyByName() {
        when(connections.existsById("planning")).thenReturn(true);

        assertThatThrownBy(() -> service.create("planning", "anything", IntegrationConnection.Direction.OUTBOUND))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("planning")
            .hasMessageContaining("already exists");

        verify(connections, never()).save(any());
    }

    @Test
    void VYB0839_AC3_refusesABlankKey() {
        assertThatThrownBy(() -> service.create("   ", "anything", IntegrationConnection.Direction.OUTBOUND))
            .isInstanceOf(IllegalArgumentException.class);

        verify(connections, never()).existsById(any());
        verify(connections, never()).save(any());
    }

    @Test
    void VYB0839_AC4_updateDetailsChangesOwnsAndDirectionOnAnExistingConnection() {
        IntegrationConnection existing = new IntegrationConnection("git", "commits and code links", IntegrationConnection.Direction.INBOUND);
        when(connections.findById("git")).thenReturn(Optional.of(existing));
        when(connections.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var updated = service.updateDetails("git", "commits, branches and PR links", IntegrationConnection.Direction.BOTH);

        assertThat(updated.getOwns()).isEqualTo("commits, branches and PR links");
        assertThat(updated.getDirection()).isEqualTo(IntegrationConnection.Direction.BOTH);
    }

    @Test
    void VYB0839_AC5_updateDetailsLeavesFieldsUnchangedWhenPassedNull() {
        IntegrationConnection existing = new IntegrationConnection("git", "commits and code links", IntegrationConnection.Direction.INBOUND);
        when(connections.findById("git")).thenReturn(Optional.of(existing));
        when(connections.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var updated = service.updateDetails("git", null, null);

        assertThat(updated.getOwns()).isEqualTo("commits and code links");
        assertThat(updated.getDirection()).isEqualTo(IntegrationConnection.Direction.INBOUND);
    }
}
