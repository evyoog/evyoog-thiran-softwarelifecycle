package com.vyoog.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.identity.AppUser;
import com.vyoog.identity.GrantResolver;
import com.vyoog.identity.ServiceAccount;
import com.vyoog.identity.ServiceAccountChecker;
import com.vyoog.identity.ServiceAccountRefusedException;
import com.vyoog.identity.ServiceAccountRepository;
import com.vyoog.identity.StepUpChecker;
import com.vyoog.identity.UserProvisioningService;
import com.vyoog.search.SearchService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** VYB-0908: search is run as the caller, and never for a service account. */
class SearchControllerTest {

    private final SearchService search = mock(SearchService.class);
    private final UserProvisioningService provisioning = mock(UserProvisioningService.class);
    private final ServiceAccountRepository accounts = mock(ServiceAccountRepository.class);
    private final SearchController controller = new SearchController(search, provisioning,
        new PrincipalGuard(new ServiceAccountChecker(accounts), mock(StepUpChecker.class), provisioning, mock(GrantResolver.class)));

    private static Jwt jwt(String azp, String email) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject("sub-1").claim("azp", azp)
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (email != null) b.claim("email", email);
        return b.build();
    }

    @Test
    void VYB0908_AC4_theQueryIsRunForTheCallersOwnUserId() {
        UUID me = UUID.randomUUID();
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(me);
        when(provisioning.upsert(eq("sub-1"), any(), any())).thenReturn(user);
        var hit = new SearchService.Result("REQUIREMENT", UUID.randomUUID(), "VY-1 x", null);
        when(search.search("pay", me)).thenReturn(List.of(hit));

        assertThat(controller.search("pay", jwt("vyoog-web", "a@example.com"))).containsExactly(hit);
    }

    @Test
    void VYB0908_AC4_aServiceAccountOrAnEmaillessTokenSearchesNothing() {
        when(accounts.findByClientId("ci-bot")).thenReturn(Optional.of(new ServiceAccount("ci", "t", "ci-bot", List.of("ci:ingest"))));
        assertThatThrownBy(() -> controller.search("x", jwt("ci-bot", null))).isInstanceOf(ServiceAccountRefusedException.class);
        assertThatThrownBy(() -> controller.search("x", jwt("stranger", null))).isInstanceOf(ServiceAccountRefusedException.class);
        verify(search, never()).search(any(), any());
    }
}
