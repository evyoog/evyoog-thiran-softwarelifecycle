package com.vyoog.testkit;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * VYB-0903 (F10): where an integration runner's database comes from — never a
 * default, never
 * anything but this machine.
 *
 * <ul>
 * <li>{@code DB_URL} unset: a throwaway Testcontainers Postgres (pgvector
 * image, schema
 * {@code vyg_requirement}) is started once per JVM and discarded with it. Needs
 * Docker.</li>
 * <li>{@code DB_URL} set: it must point at localhost / a loopback address (the
 * docker-compose
 * database). Anything else — an RDS host, a shared dev server — is refused with
 * a message
 * that names the host.</li>
 * </ul>
 *
 * The integration runners used to fall through to application.yml, whose
 * datasource default was
 * the live shared RDS instance; one of them then rewrote a real integration
 * row.
 */
public final class LocalDatabase {

    /** JDBC coordinates handed to Spring. */
    public record Coordinates(String url, String user, String password) {
    }

    private static final DockerImageName IMAGE = DockerImageName.parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres");

    private static PostgreSQLContainer<?> container;

    private LocalDatabase() {
    }

    public static Coordinates resolve() {
        return resolve(System.getenv());
    }

    /**
     * Environment passed in so the refusal rules are testable without touching the
     * real one.
     */
    public static Coordinates resolve(Map<String, String> env) {
        String url = env.get("DB_URL");
        if (url == null || url.isBlank()) {
            return throwawayContainer();
        }
        requireLocal(url);
        return new Coordinates(url, env.get("DB_USER"), env.get("DB_PASSWORD"));
    }

    /**
     * @throws IllegalStateException unless every host in the JDBC URL is localhost
     *                               or loopback
     */
    public static void requireLocal(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
            throw refuse(jdbcUrl, "it is not a jdbc:postgresql://host/db URL");
        }
        String rest = jdbcUrl.substring("jdbc:postgresql://".length());
        String query = "";
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1).toLowerCase(Locale.ROOT);
            rest = rest.substring(0, q);
        }
        if (query.contains("host=") || query.contains("hostaddr=")) {
            throw refuse(jdbcUrl, "it sets the host through a query parameter");
        }
        int slash = rest.indexOf('/');
        String authority = slash >= 0 ? rest.substring(0, slash) : rest;
        if (authority.isBlank()) {
            throw refuse(jdbcUrl, "it names no host");
        }
        for (String hostPort : authority.split(",")) {
            String host = hostOf(hostPort);
            if (!isLoopback(host)) {
                throw refuse(jdbcUrl, "host '" + host + "' is not this machine");
            }
        }
    }

    private static String hostOf(String hostPort) {
        String s = hostPort.substring(hostPort.lastIndexOf('@') + 1).trim();
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            return end > 0 ? s.substring(1, end) : s;
        }
        int colon = s.lastIndexOf(':');
        return (colon >= 0 ? s.substring(0, colon) : s).toLowerCase(Locale.ROOT);
    }

    private static boolean isLoopback(String host) {
        if (host.equals("localhost") || host.equals("::1"))
            return true;
        return host.matches("127(\\.\\d{1,3}){3}");
    }

    private static IllegalStateException refuse(String url, String why) {
        return new IllegalStateException(
                "Refusing to run an integration runner against '" + url + "': " + why + ". Runners may only use a "
                        + "local database. Unset DB_URL to use a throwaway Testcontainers Postgres (needs Docker), or point "
                        + "DB_URL at the docker-compose database on localhost (docker compose up -d, from the repository root). "
                        + "See README, 'Running the integration runners locally'.");
    }

    private static synchronized Coordinates throwawayContainer() {
        if (container == null) {
            PostgreSQLContainer<?> c = new PostgreSQLContainer<>(IMAGE)
                    .withDatabaseName("sandbox").withUsername("sandboxadmin").withPassword("vyg@2011");
            try {
                c.start();
                try (Connection conn = c.createConnection(""); Statement st = conn.createStatement()) {
                    st.execute("CREATE SCHEMA IF NOT EXISTS vyg_requirement AUTHORIZATION postgres");
                }
            } catch (Exception e) {
                throw new IllegalStateException(
                        "DB_URL is not set, so a throwaway Testcontainers Postgres was needed, but it could not be "
                                + "started (" + e.getMessage()
                                + "). Start Docker, or run `docker compose up -d` from the repository root "
                                + "and set DB_URL, DB_USER and DB_PASSWORD to the local database.",
                        e);
            }
            container = c;
        }
        String url = "jdbc:postgresql://%s:%d/%s?currentSchema=vyg_requirement,public"
                .formatted(container.getHost(), container.getMappedPort(5432), container.getDatabaseName());
        return new Coordinates(url, container.getUsername(), container.getPassword());
    }
}
