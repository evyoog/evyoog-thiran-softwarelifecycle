package com.vyoog.testkit;

import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * A Postgres container mirroring the shape of the real deployment: one schema
 * ({@code vyg_requirement}) owned by the connecting role, on the shared instance's
 * naming convention. There is no row-level security or non-owner role split here —
 * see docs/DECISIONS.md D3. Schema separation between applications is the boundary
 * this product relies on, and that boundary is infrastructure (which schema you
 * connect to), not something a unit test can exercise meaningfully.
 *
 * <p>{@code @DirtiesContext} (default AFTER_CLASS): {@code @Container}'s static field is
 * shared by every *IT subclass, but its JUnit 5 lifecycle stops and restarts the
 * container once per test class regardless — a fresh container, fresh port, every time
 * (confirmed against real Jenkins runs). Spring's context cache key, however, does not
 * depend on the declaring test class or on what a @DynamicPropertySource supplier
 * actually returns, only on which @DynamicPropertySource methods exist — and since
 * datasourceProperties() below is declared once here and inherited unchanged by every
 * subclass, every *IT class hashes to the same cache key. Without @DirtiesContext, Spring
 * reuses the previous class's cached context, DataSource and HikariCP pool wholesale —
 * pointed at a container that Testcontainers has already torn down — and every later
 * class hangs on doomed connection attempts (Hikari's timeout window) before failing.
 * This is Spring's own documented fix for exactly this combination (shared
 * @DynamicPropertySource base class + a container whose lifecycle isn't itself
 * cache-aware) — see the Spring Framework reference docs' @DynamicPropertySource section
 * and Spring Boot's Testcontainers testing docs.
 */
@DirtiesContext
@Testcontainers
@Import(PostgresFixture.StubStorage.class)
public abstract class PostgresFixture {

    @Container
    protected static final PostgreSQLContainer<?> DB =
        new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16")
                               .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("vygmicroservice")
            .withUsername("postgres")
            .withPassword("test");

    @BeforeAll
    static void createSchema() throws Exception {
        try (Connection c = DB.createConnection(""); Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS vyg_requirement AUTHORIZATION postgres");
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
            "jdbc:postgresql://%s:%d/%s?currentSchema=vyg_requirement"
                .formatted(DB.getHost(), DB.getMappedPort(5432), DB.getDatabaseName()));
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        registry.add("spring.flyway.url", DB::getJdbcUrl);
        registry.add("spring.flyway.user", DB::getUsername);
        registry.add("spring.flyway.password", DB::getPassword);
    }

    /**
     * None of the *IT classes extending this fixture exercise attachment upload/download —
     * AttachmentService is only pulled into the context as a side effect of @SpringBootTest
     * booting the whole app (AttachmentController wires it in). Its constructor does a real
     * headBucket() against vyoog.storage.endpoint (MinIO by default — see StorageConfig,
     * docs/running-minio-locally.md), which nothing in CI provides, so every full-context IT
     * needs S3Client stubbed out — same @TestConfiguration/@Primary pattern DocumentAnalysisIT
     * already uses for its own external AI dependencies.
     *
     * <p>Named {@code stubS3Client}, not {@code s3Client} — a @Bean method's name becomes the
     * bean name by default, and reusing StorageConfig's own bean name here would collide as a
     * duplicate definition (BeanDefinitionOverrideException) rather than the two coexisting as
     * separate candidates for @Primary to choose between. StorageConfig's real bean still gets
     * created too (harmless — building an S3Client does no network I/O by itself), it's just
     * never the one injected.
     */
    @TestConfiguration
    static class StubStorage {

        @Bean
        @Primary
        S3Client stubS3Client() {
            return Mockito.mock(S3Client.class);
        }
    }
}
