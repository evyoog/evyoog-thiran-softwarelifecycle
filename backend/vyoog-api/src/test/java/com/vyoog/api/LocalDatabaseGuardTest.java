package com.vyoog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vyoog.testkit.LocalDatabase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** VYB-0903 (F10): an integration runner is refused any database that is not on this machine. */
class LocalDatabaseGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:postgresql://localhost:5432/vygmicroservice?currentSchema=vyg_requirement,public",
        "jdbc:postgresql://LOCALHOST/vygmicroservice",
        "jdbc:postgresql://127.0.0.1:5433/x",
        "jdbc:postgresql://127.1.2.3/x",
        "jdbc:postgresql://[::1]:5432/x",
        "jdbc:postgresql://localhost:5432,127.0.0.1:5433/x"})
    void VYB0903_AC1_localUrlsAreAccepted(String url) {
        assertThatCode(() -> LocalDatabase.requireLocal(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:postgresql://vyg-batch-1.example.ap-south-1.rds.amazonaws.com:5432/vygmicroservice",
        "jdbc:postgresql://db.internal:5432/x",
        "jdbc:postgresql://10.0.0.5:5432/x",
        "jdbc:postgresql://localhost.evil.example:5432/x",
        "jdbc:postgresql://127.0.0.1.evil.example/x",
        "jdbc:postgresql://localhost:5432,remote.example:5432/x",
        "jdbc:postgresql://user@remote.example/x",
        "jdbc:postgresql:///x?host=remote.example",
        "jdbc:postgresql://localhost/x?hostaddr=10.0.0.5",
        "jdbc:mysql://localhost/x",
        "postgres://localhost/x",
        ""})
    void VYB0903_AC1_anythingElseIsRefused(String url) {
        assertThatThrownBy(() -> LocalDatabase.requireLocal(url))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Refusing to run");
    }

    @Test
    void VYB0903_AC1_theRefusalNamesTheOffendingHostAndWhatToDoInstead() {
        assertThatThrownBy(() -> LocalDatabase.requireLocal("jdbc:postgresql://prod.example.com:5432/x"))
            .hasMessageContaining("prod.example.com").hasMessageContaining("docker compose up -d")
            .hasMessageContaining("Testcontainers");
    }

    @Test
    void VYB0903_AC1_aSetLocalDbUrlIsUsedAsIsWithItsCredentials() {
        var db = LocalDatabase.resolve(Map.of(
            "DB_URL", "jdbc:postgresql://localhost:5432/vygmicroservice", "DB_USER", "u", "DB_PASSWORD", "p"));
        assertThat(db.url()).isEqualTo("jdbc:postgresql://localhost:5432/vygmicroservice");
        assertThat(db.user()).isEqualTo("u");
        assertThat(db.password()).isEqualTo("p");
    }

    @Test
    void VYB0903_AC1_aSetNonLocalDbUrlIsRefusedBeforeAnythingConnects() {
        assertThatThrownBy(() -> LocalDatabase.resolve(Map.of(
            "DB_URL", "jdbc:postgresql://vyg-batch-1.example.rds.amazonaws.com:5432/x", "DB_USER", "u", "DB_PASSWORD", "p")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("rds.amazonaws.com");
    }
}
