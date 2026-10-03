package com.vyoog.api;

import org.springframework.boot.test.context.SpringBootTest;

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

    static {
        TestDatabaseProperties.exportToSystemProperties(); // before the context starts; see that class for why
    }
}
