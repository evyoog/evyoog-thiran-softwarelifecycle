package com.vyoog.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vyoog.api.config.RequiredSecretsEnvironmentPostProcessor.MissingRequiredSecretsException;
import com.vyoog.api.config.RequiredSecretsEnvironmentPostProcessor.RequiredSecret;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * VYB-0900 (D22, F01/F07): no committed file carries a real credential, and the application
 * refuses to start when a required secret is unset. The startup tests boot a real
 * {@code SpringApplication} against the real application.yml (plus META-INF/spring.factories),
 * with no database — only the post-processor is under test.
 */
class RequiredSecretsStartupTest {

    @Configuration
    static class Bare {}

    static Stream<RequiredSecret> requiredSecrets() {
        return RequiredSecretsEnvironmentPostProcessor.REQUIRED.stream();
    }

    /** Command-line args beat env vars and application.yml, so the run is independent of the host shell. */
    private static String[] args(RequiredSecret missing, String missingValue) {
        List<String> args = new ArrayList<>();
        for (RequiredSecret s : RequiredSecretsEnvironmentPostProcessor.REQUIRED) {
            String value = s.equals(missing) ? missingValue : "fake-" + s.envVar().toLowerCase();
            args.add("--" + s.property() + "=" + value);
        }
        return args.toArray(String[]::new);
    }

    private static ConfigurableApplicationContext start(String... args) {
        return new SpringApplicationBuilder(Bare.class)
            .web(WebApplicationType.NONE)
            .registerShutdownHook(false)
            .run(args);
    }

    @Test
    void VYB0900_AC1_startsWhenEverySecretIsSet() {
        try (ConfigurableApplicationContext ctx = start(args(null, null))) {
            assertThat(ctx.isActive()).isTrue();
        }
    }

    @ParameterizedTest
    @MethodSource("requiredSecrets")
    void VYB0900_AC1_startupFailsNamingTheVariableWhenOneSecretIsEmpty(RequiredSecret secret) {
        assertThatThrownBy(() -> start(args(secret, "")))
            .isInstanceOf(MissingRequiredSecretsException.class)
            .hasMessageContaining(secret.envVar())
            .satisfies(e -> assertThat(((MissingRequiredSecretsException) e).envVars())
                .containsExactly(secret.envVar()));
    }

    @ParameterizedTest
    @MethodSource("requiredSecrets")
    void VYB0900_AC1_aBlankValueCountsAsMissing(RequiredSecret secret) {
        assertThatThrownBy(() -> start(args(secret, "   ")))
            .isInstanceOf(MissingRequiredSecretsException.class)
            .hasMessageContaining(secret.envVar());
    }

    @Test
    void VYB0900_AC1_everyMissingSecretIsListedAtOnce() {
        List<String> all = RequiredSecretsEnvironmentPostProcessor.REQUIRED.stream()
            .map(RequiredSecret::envVar).toList();
        String[] noneSet = RequiredSecretsEnvironmentPostProcessor.REQUIRED.stream()
            .map(s -> "--" + s.property() + "=").toArray(String[]::new);
        assertThatThrownBy(() -> start(noneSet))
            .isInstanceOf(MissingRequiredSecretsException.class)
            .satisfies(e -> assertThat(((MissingRequiredSecretsException) e).envVars())
                .containsExactlyInAnyOrderElementsOf(all));
    }

    @Test
    void VYB0900_AC2_theErrorNeverEchoesASecretValue() {
        RequiredSecret missing = RequiredSecretsEnvironmentPostProcessor.REQUIRED.get(0);
        assertThatThrownBy(() -> start(args(missing, "")))
            .hasMessageNotContaining("fake-");
    }

    @Test
    void VYB0900_AC3_applicationYmlCarriesNoCredentialDefaults() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"));
        PropertySource<?> yml = sources.get(0);
        for (RequiredSecret s : RequiredSecretsEnvironmentPostProcessor.REQUIRED) {
            assertThat(String.valueOf(yml.getProperty(s.property())))
                .as("%s must read ${%s:} with an empty default", s.property(), s.envVar())
                .isEqualTo("${" + s.envVar() + ":}");
        }
        String raw = new String(new ClassPathResource("application.yml").getInputStream().readAllBytes());
        assertThat(raw).doesNotContain("rds.amazonaws.com");
    }
}
