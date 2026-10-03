package com.vyoog.api;

import com.vyoog.testkit.LocalDatabase;

/**
 * VYB-0903/0907: the one place a test that boots the whole application gets its database. Never
 * from application.yml, and only ever a local one ({@link LocalDatabase}: a throwaway
 * Testcontainers Postgres, or {@code DB_URL} if it points at this machine). The three secrets the
 * application refuses to start without (D22) get obviously fake values unless the environment sets them.
 *
 * <p>Exported as <em>system properties</em>, from a static initializer in the test base classes, and
 * not through {@code @DynamicPropertySource}: the startup check for required configuration
 * ({@code RequiredSecretsEnvironmentPostProcessor}) runs while Spring Boot prepares the environment,
 * before the test framework adds dynamic properties, so it would not see them and would refuse to
 * start. System properties are visible to it. A property already set (by the environment or the
 * command line) is left alone.
 */
public final class TestDatabaseProperties {

    private TestDatabaseProperties() {}

    public static synchronized void exportToSystemProperties() {
        LocalDatabase.Coordinates db = LocalDatabase.resolve();
        setIfAbsent("spring.datasource.url", db.url());
        setIfAbsent("spring.datasource.username", db.user());
        setIfAbsent("spring.datasource.password", db.password());
        fakeUnlessSet("vyoog.keycloak.ropc-client-secret", "KEYCLOAK_ROPC_CLIENT_SECRET");
        fakeUnlessSet("vyoog.internal.impersonation-client-secret", "KEYCLOAK_IMPERSONATION_CLIENT_SECRET");
        fakeUnlessSet("vyoog.internal.sso-shared-secret", "INTERNAL_SSO_SHARED_SECRET");
    }

    private static void setIfAbsent(String property, String value) {
        // The resolved coordinates always win over a stale value from an earlier call in this JVM only
        // if none was set; a different database in the same JVM is not supported.
        if (System.getProperty(property) == null && value != null) System.setProperty(property, value);
    }

    private static void fakeUnlessSet(String property, String envVar) {
        String fromEnv = System.getenv(envVar);
        if (fromEnv == null || fromEnv.isBlank()) {
            setIfAbsent(property, "verification-runner-fake-" + envVar.toLowerCase());
        }
    }
}
