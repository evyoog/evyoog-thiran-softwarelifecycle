package com.vyoog.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * VYB-0901 (F04): a service account is a <em>registered</em> one. The old rule also counted any
 * token without an {@code email} claim, so an unregistered client-credentials token from anywhere in the
 * realm could call the CI ingestion endpoints.
 */
class ServiceAccountCheckerTest {

    private final ServiceAccountRepository repo = mock(ServiceAccountRepository.class);
    private final ServiceAccountChecker checker = new ServiceAccountChecker(repo);

    private void register(String clientId, String... scopes) {
        when(repo.findByClientId(clientId))
            .thenReturn(Optional.of(new ServiceAccount("ci", "test", clientId, List.of(scopes))));
    }

    @Test
    void VYB0901_AC2_anUnregisteredTokenWithNoEmailIsNotAServiceAccount() {
        assertThat(checker.isServiceAccount("some-other-client")).isFalse();
        assertThat(checker.isServiceAccount(null)).isFalse();
    }

    @Test
    void VYB0901_AC2_aRegisteredClientIsAServiceAccountWhateverItsEmailClaimSays() {
        register("ci-bot");
        assertThat(checker.isServiceAccount("ci-bot")).isTrue();
        assertThat(checker.isPerson("ci-bot", "ci@example.com")).isFalse();
        assertThat(checker.isPerson("ci-bot", null)).isFalse();
    }

    @Test
    void VYB0901_AC2_aPersonIsAnUnregisteredCallerWithAnEmail() {
        assertThat(checker.isPerson("vyoog-web", "a@example.com")).isTrue();
    }

    @Test
    void VYB0901_AC2_aTokenThatIsNeitherRegisteredNorHasAnEmailIsNotAPersonEither() {
        assertThat(checker.isPerson("some-other-client", null)).isFalse();
        assertThat(checker.isPerson("some-other-client", "  ")).isFalse();
    }

    @Test
    void VYB0901_AC2_scopesAreOnlyEverHeldByARegisteredAccount() {
        register("ci-bot", KnownServiceScopes.CI_INGEST);
        register("no-scopes");
        assertThat(checker.hasScope("ci-bot", KnownServiceScopes.CI_INGEST)).isTrue();
        assertThat(checker.hasScope("ci-bot", KnownServiceScopes.WEBHOOK_GIT)).isFalse();
        assertThat(checker.hasScope("no-scopes", KnownServiceScopes.CI_INGEST)).isFalse();
        assertThat(checker.hasScope("unregistered", KnownServiceScopes.CI_INGEST)).isFalse();
    }
}
