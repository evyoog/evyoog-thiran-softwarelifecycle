package com.vyoog.api;

import com.vyoog.testkit.LocalDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * VYB-0903 (F10): every {@code *Runner} in this package extends this. It gives the full
 * application context a database that is provably local (see {@link LocalDatabase}) and
 * never lets it fall back to application.yml, which has no datasource default (D22) and
 * once had the live RDS instance as one.
 *
 * <p>The three secrets the application refuses to start without (D22) get obviously fake
 * values here unless the environment already supplies them, so a runner that does not care
 * about Keycloak still boots and one that does (AuthEndpointVerificationRunner) keeps its own.
 * {@code RunnersUseLocalDatabaseTest} fails the build if a runner forgets to extend this.
 */
@SpringBootTest
abstract class VerificationRunnerBase {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        LocalDatabase.Coordinates db = LocalDatabase.resolve();
        registry.add("spring.datasource.url", db::url);
        registry.add("spring.datasource.username", db::user);
        registry.add("spring.datasource.password", db::password);
        fakeUnlessSet(registry, "vyoog.keycloak.ropc-client-secret", "KEYCLOAK_ROPC_CLIENT_SECRET");
        fakeUnlessSet(registry, "vyoog.internal.impersonation-client-secret", "KEYCLOAK_IMPERSONATION_CLIENT_SECRET");
        fakeUnlessSet(registry, "vyoog.internal.sso-shared-secret", "INTERNAL_SSO_SHARED_SECRET");
    }

    private static void fakeUnlessSet(DynamicPropertyRegistry registry, String property, String envVar) {
        String fromEnv = System.getenv(envVar);
        if (fromEnv == null || fromEnv.isBlank()) {
            registry.add(property, () -> "verification-runner-fake-" + envVar.toLowerCase());
        }
    }
}
