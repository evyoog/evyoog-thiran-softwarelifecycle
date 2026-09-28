package com.vyoog.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * VYB-0464: team roles, which exist for one reason — Planning refuses an assignment made
 * by anybody but a lead of the assignee's team. Everything here is about that rule not
 * misfiring: never locking a team out of assignment entirely, and never applying a
 * restriction where nobody has said who leads.
 *
 * <p>Lenient stubbing: {@link JdbcTemplate}'s varargs overloads make strict argument
 * matching a fight with the mock framework rather than a check on this class.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeamRoleTest {

    @Mock JdbcTemplate jdbc;
    @Mock TeamRepository teamRepository;

    TeamService service;
    UUID team;
    UUID actor;
    UUID assignee;

    @BeforeEach
    void setUp() {
        service = new TeamService(teamRepository, jdbc);
        team = UUID.randomUUID();
        actor = UUID.randomUUID();
        assignee = UUID.randomUUID();
    }

    /** Counts are the only thing the permission rule reads; this stubs them in call order. */
    private void counts(Long... values) {
        var stub = when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
            .thenReturn(values[0]);
        for (int i = 1; i < values.length; i++) stub = stub.thenReturn(values[i]);
    }

    @Test
    void VYB0464_AC1_somebodyInATeamNobodyLeadsCanBeAssignedByAnyone() {
        counts(0L); // no lead over any team the assignee belongs to

        // Permissive where the structure is absent: refusing here would make assignment
        // impossible until every team has been given a lead, which is a lockout, not a
        // permission model.
        assertThat(service.mayAssign(actor, assignee)).isTrue();
    }

    @Test
    void VYB0464_AC1_whereALeadExistsOnlyThatLeadMayAssign() {
        counts(1L, 0L); // a lead exists, but the actor is not one of them

        assertThat(service.mayAssign(actor, assignee)).isFalse();
    }

    @Test
    void VYB0464_AC1_theLeadOfTheirTeamMayAssign() {
        counts(1L, 1L);

        assertThat(service.mayAssign(actor, assignee)).isTrue();
    }

    @Test
    void VYB0464_AC2_demotingTheOnlyLeadIsRefusedRatherThanLeavingATeamUnled() {
        counts(0L); // nobody else holds LEAD

        assertThatThrownBy(() -> service.setRole(team, actor, "MEMBER"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("only lead");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void VYB0464_AC2_demotingALeadIsFineWhileAnotherRemains() {
        counts(1L);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        service.setRole(team, actor, "MEMBER");

        verify(jdbc).update(contains("UPDATE team_member SET role"), any(Object[].class));
    }

    @Test
    void VYB0464_AC2_promotingToLeadNeverChecksTheLeadCountAtAll() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        service.setRole(team, actor, "LEAD");

        // Adding a lead cannot leave a team unled, so the guard must not run — a stubbed
        // count of zero here would otherwise refuse the very fix for an unled team.
        verify(jdbc, never()).queryForObject(anyString(), eq(Long.class), any(Object[].class));
    }

    @Test
    void VYB0464_AC2_aRoleThatIsNotLeadOrMemberIsRefused() {
        assertThatThrownBy(() -> service.setRole(team, actor, "ADMIN"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void VYB0464_AC2_settingTheRoleOfSomebodyNotInTheTeamSaysSoRatherThanSilentlyDoingNothing() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        assertThatThrownBy(() -> service.setRole(team, actor, "LEAD"))
            .isInstanceOf(NoSuchElementException.class);
    }
}
