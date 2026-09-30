package com.vyoog.api.config;

import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * D22 (VYB-0900): no real credential is committed anywhere, so application.yml carries
 * empty defaults for every secret and this check refuses to start the application when
 * one is still empty. Runs after the config files are loaded (lowest precedence), so a
 * value from an environment variable, {@code .env}, the secrets manager or a command-line
 * argument all count; only "nobody set it" fails.
 *
 * <p>The failure names the environment variable, not the Spring property, because that is
 * what the person starting the app has to set. It never echoes a value.
 */
public class RequiredSecretsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** One required secret: the Spring property it binds to and the variable that feeds it. */
    public record RequiredSecret(String property, String envVar) {}

    public static final List<RequiredSecret> REQUIRED = List.of(
        new RequiredSecret("spring.datasource.url", "DB_URL"),
        new RequiredSecret("spring.datasource.username", "DB_USER"),
        new RequiredSecret("spring.datasource.password", "DB_PASSWORD"),
        new RequiredSecret("vyoog.keycloak.ropc-client-secret", "KEYCLOAK_ROPC_CLIENT_SECRET"),
        new RequiredSecret("vyoog.internal.impersonation-client-secret", "KEYCLOAK_IMPERSONATION_CLIENT_SECRET"),
        new RequiredSecret("vyoog.internal.sso-shared-secret", "INTERNAL_SSO_SHARED_SECRET"));

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> missing = REQUIRED.stream()
            .filter(s -> isBlank(environment.getProperty(s.property())))
            .map(RequiredSecret::envVar)
            .toList();
        if (!missing.isEmpty()) {
            throw new MissingRequiredSecretsException(missing);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /** Startup failure listing every unset secret at once, by environment variable name. */
    public static class MissingRequiredSecretsException extends RuntimeException {
        private final List<String> envVars;

        public MissingRequiredSecretsException(List<String> envVars) {
            super("Required configuration is not set: " + String.join(", ", envVars)
                + ". Set each as an environment variable (locally: copy .env.example to .env). "
                + "No credential has a default (D22).");
            this.envVars = List.copyOf(envVars);
        }

        public List<String> envVars() {
            return envVars;
        }
    }
}
