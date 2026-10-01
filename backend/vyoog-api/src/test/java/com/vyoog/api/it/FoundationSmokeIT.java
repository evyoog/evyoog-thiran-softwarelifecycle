package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * VYB-0907 / VYB-0010: the application starts against a real database, Flyway applied every
 * migration into the {@code vyg_requirement} schema, and the extensions the code relies on exist.
 */
class FoundationSmokeIT extends IntegrationTestBase {

    @Test
    void VYB0907_AC1_everyMigrationInTheRepositoryWasApplied() throws Exception {
        Path dir = Path.of("..", "..", "database", "migrations");
        List<String> onDisk;
        try (var files = Files.list(dir)) {
            onDisk = files.map(p -> p.getFileName().toString()).filter(n -> n.matches("V\\d+__.*\\.sql")).sorted().toList();
        }
        assertThat(onDisk).as("migrations found in database/migrations").hasSizeGreaterThanOrEqualTo(34);

        Integer applied = jdbc.queryForObject(
            "SELECT count(*) FROM vyg_requirement.flyway_schema_history WHERE success AND version IS NOT NULL", Integer.class);
        assertThat(applied).as("successfully applied versioned migrations").isEqualTo(onDisk.size());
    }

    @Test
    void VYB0907_AC1_theSchemaAndTheExtensionsTheCodeNeedsExist() {
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'vyg_requirement' AND table_name IN "
                + "('requirement','requirement_revision','trace_link','release','baseline','review','change_request')",
            Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForList("SELECT extname FROM pg_extension", String.class))
            .contains("vector", "pg_trgm", "pgcrypto");
    }

    @Test
    void VYB0907_AC1_theSearchPathHoldsTheApplicationSchemaAndPublicSoTheVectorOperatorsResolve() {
        String path = jdbc.queryForObject("SHOW search_path", String.class);
        assertThat(path).contains("vyg_requirement").contains("public");
        // the pgvector cosine-distance operator is registered unqualified in public; using it proves the path works
        assertThat(jdbc.queryForObject("SELECT '[1,0]'::vector <=> '[1,0]'::vector", Double.class)).isZero();
    }
}
