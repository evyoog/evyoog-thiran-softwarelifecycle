package com.vyoog.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.vyoog.platform.audit.AuditService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * VYB-0700: an administrator adding a colleague before that colleague's first sign-in,
 * and the sign-in that claims the row afterwards.
 *
 * <p>The claim is the part worth testing hardest. Vyoog creates no identity and no
 * credential here — only the mirror row — so the entire value of creating one early is
 * that the grants prepared against it survive the moment Keycloak finally supplies a
 * real subject. If that hand-off is wrong, the feature silently does nothing: the person
 * signs in, gets a second row, and none of the access set up for them applies.
 */
@ExtendWith(MockitoExtension.class)
class AppUserCreationTest {

    @Mock AppUserRepository users;
    @Mock AccessGrantRepository grants;
    @Mock AccessGrantService accessGrants;
    @Mock JdbcTemplate jdbc;
    @Mock AuditService audit;

    AppUserService service;
    UserProvisioningService provisioning;
    UUID actor;

    @BeforeEach
    void setUp() {
        service = new AppUserService(users, grants, accessGrants, jdbc, audit);
        provisioning = new UserProvisioningService(users);
        actor = UUID.randomUUID();
        lenient().when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void VYB0700_AC1_anAdministratorCreatedUserIsLocalAndCarriesNoRealSubjectYet() {
        lenient().when(users.findAllByEmailIgnoreCase("dana@vyoog.test")).thenReturn(List.of());

        AppUser created = service.createLocal("  dana@vyoog.test ", "  Dana Reyes  ", actor);

        assertThat(created.getEmail()).isEqualTo("dana@vyoog.test");   // trimmed
        assertThat(created.getDisplayName()).isEqualTo("Dana Reyes");
        assertThat(created.getSource()).isEqualTo("LOCAL");
        // Not a Keycloak subject — the schema needs one, and there isn't a real one yet.
        assertThat(created.getSubject()).startsWith(AppUserService.UNCLAIMED_SUBJECT_PREFIX);
        assertThat(created.getStatus()).isEqualTo("ACTIVE");
        verify(audit).record(eq(actor), eq("user.created"), eq("APP_USER"), any(), isNull(), anyMap());
    }

    @Test
    void VYB0700_AC1_aRepeatedEmailIsAcceptedRatherThanRefused() {
        // Deliberate: adding someone must not be blocked by whatever is already in the
        // directory. The cost is handled at sign-in, not by refusing the administrator here.
        AppUser existing = new AppUser("sub-1", "dana@vyoog.test", "Dana Reyes");
        lenient().when(users.findAllByEmailIgnoreCase("dana@vyoog.test")).thenReturn(List.of(existing));

        AppUser second = service.createLocal("dana@vyoog.test", "Dana R", actor);

        assertThat(second.getEmail()).isEqualTo("dana@vyoog.test");
        verify(users).save(second);
    }

    @Test
    void VYB0700_AC2_twoPreparedRowsOnOneEmailClaimNeitherRatherThanGuessingWhichRolesApply() {
        // Allowing duplicates makes this reachable. Claiming either would hand this person
        // whichever set of roles happened to sort first — a silently arbitrary grant.
        AppUser first = new AppUser(AppUserService.UNCLAIMED_SUBJECT_PREFIX + UUID.randomUUID(),
            "dana@vyoog.test", "Dana Reyes");
        AppUser second = new AppUser(AppUserService.UNCLAIMED_SUBJECT_PREFIX + UUID.randomUUID(),
            "dana@vyoog.test", "Dana R");
        when(users.findBySubject("keycloak-sub-dana")).thenReturn(Optional.empty());
        when(users.findAllByEmailIgnoreCase("dana@vyoog.test")).thenReturn(List.of(first, second));

        AppUser after = provisioning.upsert("keycloak-sub-dana", "dana@vyoog.test", "Dana Reyes");

        assertThat(after).isNotSameAs(first).isNotSameAs(second);
        assertThat(after.getSubject()).isEqualTo("keycloak-sub-dana");
        assertThat(first.getSubject()).startsWith(AppUserService.UNCLAIMED_SUBJECT_PREFIX);  // untouched
        assertThat(second.getSubject()).startsWith(AppUserService.UNCLAIMED_SUBJECT_PREFIX);
    }

    @Test
    void VYB0700_AC1_blankInputIsRefusedRatherThanStoredAsAnEmptyDirectoryRow() {
        assertThatThrownBy(() -> service.createLocal("   ", "Dana", actor))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("email");
        assertThatThrownBy(() -> service.createLocal("dana@vyoog.test", "  ", actor))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("display name");
    }

    @Test
    void VYB0700_AC2_firstSignInClaimsThePreparedRowInsteadOfCreatingASecondOne() {
        AppUser prepared = new AppUser(AppUserService.UNCLAIMED_SUBJECT_PREFIX + UUID.randomUUID(),
            "dana@vyoog.test", "Dana Reyes");
        prepared.markLocallyCreated();
        when(users.findBySubject("keycloak-sub-dana")).thenReturn(Optional.empty());
        when(users.findAllByEmailIgnoreCase("dana@vyoog.test")).thenReturn(List.of(prepared));

        AppUser after = provisioning.upsert("keycloak-sub-dana", "dana@vyoog.test", "Dana Reyes");

        // Same row — so every grant an administrator prepared against it still applies.
        assertThat(after).isSameAs(prepared);
        assertThat(after.getSubject()).isEqualTo("keycloak-sub-dana");
        assertThat(after.getSource()).isEqualTo("SSO"); // no longer a placeholder
        assertThat(after.getLastSeenAt()).isNotNull();
        verify(users).save(prepared);
    }

    @Test
    void VYB0700_AC2_anEmailMatchNeverClaimsARowThatAlreadyHasARealSubject() {
        // The account-takeover case: someone else's IdP email agreeing with an existing
        // signed-in user must not hand over that user's row, and therefore their grants.
        AppUser realUser = new AppUser("keycloak-sub-existing", "dana@vyoog.test", "Dana Reyes");
        when(users.findBySubject("keycloak-sub-attacker")).thenReturn(Optional.empty());
        when(users.findAllByEmailIgnoreCase("dana@vyoog.test")).thenReturn(List.of(realUser));

        AppUser after = provisioning.upsert("keycloak-sub-attacker", "dana@vyoog.test", "Someone Else");

        assertThat(after).isNotSameAs(realUser);
        assertThat(after.getSubject()).isEqualTo("keycloak-sub-attacker");
        assertThat(realUser.getSubject()).isEqualTo("keycloak-sub-existing"); // untouched
    }

    @Test
    void VYB0700_AC2_aKnownSubjectStillMatchesOnSubjectAndIgnoresTheEmailPath() {
        AppUser known = new AppUser("keycloak-sub-dana", "old@vyoog.test", "Dana Reyes");
        when(users.findBySubject("keycloak-sub-dana")).thenReturn(Optional.of(known));

        AppUser after = provisioning.upsert("keycloak-sub-dana", "new@vyoog.test", "Dana R");

        assertThat(after).isSameAs(known);
        assertThat(after.getEmail()).isEqualTo("new@vyoog.test"); // mutable attributes still refresh
        verify(users, never()).findAllByEmailIgnoreCase(any());
    }

    /**
     * The bug this screen shipped with: {@code setManager} has rejected self-reference
     * since VYB-0792 and {@code setDelegate} never did, though delegation is the one where
     * it actually causes harm — a user on leave whose work routes back to themselves.
     */
    @Test
    void VYB0706_AC1_aUserCannotBeTheirOwnDelegate() {
        UUID id = UUID.randomUUID();
        AppUser user = new AppUser("sub-1", "dana@vyoog.test", "Dana Reyes");
        when(users.findById(id)).thenReturn(Optional.of(user));
        when(users.existsById(id)).thenReturn(true);

        assertThatThrownBy(() -> service.setDelegate(id, id, actor))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be their own delegate");
        assertThat(user.getDelegateId()).isNull();
        verify(users, never()).save(any());
    }

    @Test
    void VYB0706_AC1_delegatingToSomebodyElseStillWorksAndClearingStillWorks() {
        UUID id = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        AppUser user = new AppUser("sub-1", "dana@vyoog.test", "Dana Reyes");
        when(users.findById(id)).thenReturn(Optional.of(user));
        when(users.existsById(other)).thenReturn(true);

        service.setDelegate(id, other, actor);
        assertThat(user.getDelegateId()).isEqualTo(other);

        service.setDelegate(id, null, actor);
        assertThat(user.getDelegateId()).isNull();
    }

    @Test
    void VYB0700_AC1_creatingWithARoleGrantsItImmediatelyWithNoIdentityProviderInvolved() {
        lenient().when(users.findAllByEmailIgnoreCase("dana@vyoog.test")).thenReturn(List.of());

        AppUser created = service.createLocalWithRole("dana@vyoog.test", "Dana Reyes",
            AccessRole.DEVELOPER, ScopeType.PLATFORM, null, actor);

        // The grant is Vyoog's own access_grant row — it applies now, not after a sign-in.
        verify(accessGrants).grant(created.getId(), AccessRole.DEVELOPER, ScopeType.PLATFORM, null, null, actor);
    }

    @Test
    void VYB0700_AC1_creatingWithNoRoleAddsTheRowAndGrantsNothing() {
        lenient().when(users.findAllByEmailIgnoreCase("dana@vyoog.test")).thenReturn(List.of());

        service.createLocalWithRole("dana@vyoog.test", "Dana Reyes", null, null, null, actor);

        verify(accessGrants, never()).grant(any(), any(), any(), any(), any(), any());
    }

    @Test
    void VYB0700_AC1_aRefusedGrantTakesTheDirectoryRowWithIt() {
        // Otherwise an administrator is left with a user who exists but has no access and
        // no message saying why — the half-state the single transaction exists to prevent.
        lenient().when(users.findAllByEmailIgnoreCase("dana@vyoog.test")).thenReturn(List.of());
        doThrow(new IllegalArgumentException("An external user's grant must have an expiry"))
            .when(accessGrants).grant(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.createLocalWithRole(
            "dana@vyoog.test", "Dana Reyes", AccessRole.TESTER, ScopeType.PLATFORM, null, actor))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void VYB0700_AC1_aCreatedUserAppearsInTheDirectoryWithNoGrantsYet() {
        AppUser created = new AppUser(AppUserService.UNCLAIMED_SUBJECT_PREFIX + UUID.randomUUID(),
            "dana@vyoog.test", "Dana Reyes");
        created.markLocallyCreated();
        when(users.findAll()).thenReturn(List.of(created));
        // The count is a direct SQL aggregate, not a repository call — see activeGrantCount.
        when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(0L);

        List<AppUserService.DirectoryRow> directory = service.directory();

        assertThat(directory).hasSize(1);
        assertThat(directory.get(0).user().getSource()).isEqualTo("LOCAL");
        assertThat(directory.get(0).activeGrantCount()).isZero();
    }
}
