package com.vyoog.api;

import com.vyoog.api.rehearsal.LoadStats;
import com.vyoog.api.rehearsal.RehearsalTokens;
import com.vyoog.detection.DetectionSweepService;
import com.vyoog.identity.AccessGrantService;
import com.vyoog.identity.AccessRole;
import com.vyoog.identity.ScopeType;
import com.vyoog.identity.UserProvisioningService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * VYB-0959 (F11): the load rehearsal over the real HTTP layer. It boots the whole application on a real port against a real local
 * PostgreSQL, seeds a data set (set-based SQL, so 50,000 rows take seconds), and drives concurrent people through a mixed read and
 * write scenario with real signed bearer tokens (a throwaway key stands in for Keycloak, see {@link RehearsalTokens}), then times the
 * nightly detection sweep. Every number is measured; nothing is estimated.
 *
 * <p>Deliberately named so neither Surefire nor Failsafe picks it up: run it by name, against a database made for it, never a shared
 * or deployed one ({@code LocalDatabase} refuses a non-local database).
 *
 * <pre>
 * DB_URL='jdbc:postgresql://localhost:5432/vygload?currentSchema=vyg_requirement,public' DB_USER=postgres DB_PASSWORD=... \
 *   REHEARSAL_LABEL=pilot REHEARSAL_REQUIREMENTS=2000 \
 *   ./mvnw -B -pl vyoog-api -am test -Dtest=LoadRehearsalHttpRunner -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * Settings (environment): {@code REHEARSAL_REQUIREMENTS} (default 2000), {@code REHEARSAL_LABEL}, and {@code REHEARSAL_PHASES} as
 * {@code name:people:thinkMinMs:thinkMaxMs:seconds,...} (default one realistic phase of 15 people, one stress phase of 60).
 * The report is printed and written to {@code target/rehearsal/<label>.txt}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(LoadRehearsalHttpRunner.Config.class)
class LoadRehearsalHttpRunner extends VerificationRunnerBase {

    static final RehearsalTokens TOKENS = new RehearsalTokens();

    @TestConfiguration
    static class Config {
        @Bean @Primary JwtDecoder rehearsalJwtDecoder() { return TOKENS.decoder(); }
        @Bean @Primary S3Client rehearsalS3() { return Mockito.mock(S3Client.class); }
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserProvisioningService users;
    @Autowired AccessGrantService grants;
    @Autowired DetectionSweepService sweep;

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"");
    private static final Pattern REVISION = Pattern.compile("\"revision\"\\s*:\\s*(\\d+)");

    private record Phase(String name, int people, int thinkMin, int thinkMax, int seconds) {}

    private final StringBuilder report = new StringBuilder();

    private void say(String line) {
        System.out.println("[rehearsal] " + line);
        report.append(line).append('\n');
    }

    @Test
    void rehearse() throws Exception {
        int requirementCount = Integer.parseInt(env("REHEARSAL_REQUIREMENTS", "2000"));
        String label = env("REHEARSAL_LABEL", "rehearsal");
        List<Phase> phases = new ArrayList<>();
        for (String spec : env("REHEARSAL_PHASES", "realistic:15:1000:3000:60,stress:60:50:200:30").split(",")) {
            String[] f = spec.split(":");
            phases.add(new Phase(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4])));
        }

        say("# Load rehearsal: " + label);
        say("machine: " + Runtime.getRuntime().availableProcessors() + " cores, JVM max heap " + Runtime.getRuntime().maxMemory() / (1024 * 1024)
            + " MB, " + jdbc.queryForObject("SELECT version()", String.class).split(" on ")[0]
            + ", shared_buffers " + jdbc.queryForObject("SHOW shared_buffers", String.class) + ", app pool 20 connections; load generator, application and database share this one machine");

        long seedStart = System.nanoTime();
        Seed seed = seed(requirementCount);
        say(String.format("seeded %,d requirements (%,d with criteria, %,d embeddings) in %.1f s; database %s",
            requirementCount, jdbc.queryForObject("SELECT count(DISTINCT requirement_id) FROM acceptance_criterion", Long.class),
            jdbc.queryForObject("SELECT count(*) FROM requirement_embedding", Long.class), (System.nanoTime() - seedStart) / 1e9,
            jdbc.queryForObject("SELECT pg_size_pretty(pg_database_size(current_database()))", String.class)));
        jdbc.execute("ANALYZE");

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).executor(Executors.newCachedThreadPool()).build();
        for (Phase phase : phases) runPhase(client, phase, seed);

        say("");
        say("## Nightly detection sweep (the same method the 02:00 job calls)");
        long findingsBefore = jdbc.queryForObject("SELECT count(*) FROM finding", Long.class);
        int limitMinutes = Integer.parseInt(env("REHEARSAL_SWEEP_MINUTES", "20"));
        long t0 = System.nanoTime();
        var sweepRun = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "rehearsal-sweep"); t.setDaemon(true); return t; }).submit(() -> { sweep.nightlySweep(); return null; });
        try {
            sweepRun.get(limitMinutes, TimeUnit.MINUTES);
            say(String.format("sweep over %,d requirements: %.1f s; findings %,d before, %,d after", requirementCount, (System.nanoTime() - t0) / 1e9,
                findingsBefore, jdbc.queryForObject("SELECT count(*) FROM finding", Long.class)));
        } catch (java.util.concurrent.TimeoutException e) {
            say(String.format("sweep over %,d requirements DID NOT FINISH within %d minutes (stopped; findings %,d before, %,d when stopped)", requirementCount,
                limitMinutes, findingsBefore, jdbc.queryForObject("SELECT count(*) FROM finding", Long.class)));
            jdbc.queryForList("SELECT pg_cancel_backend(pid) FROM pg_stat_activity WHERE state = 'active' AND pid <> pg_backend_pid() AND query ILIKE '%requirement_embedding%'");
        }

        Path out = Path.of("target", "rehearsal", label + ".txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
        System.out.println("[rehearsal] report written to " + out.toAbsolutePath());
    }

    // ------------------------------------------------------------------ data

    private record Seed(UUID capabilityId, List<UUID> capabilityIds, List<UUID> requirementIds, UUID productId) {}

    private Seed seed(int count) {
        String tag = UUID.randomUUID().toString().substring(0, 6);
        UUID product = jdbc.queryForObject("INSERT INTO product (key, name) VALUES (?, ?) RETURNING id", UUID.class, "RL" + tag, "Rehearsal product " + tag);
        List<UUID> capabilities = new ArrayList<>();
        for (int a = 1; a <= 3; a++) {
            UUID app = jdbc.queryForObject("INSERT INTO application (product_id, name) VALUES (?, ?) RETURNING id", UUID.class, product, "App " + a);
            for (int c = 1; c <= 4; c++) {
                capabilities.add(jdbc.queryForObject("INSERT INTO capability (application_id, name) VALUES (?, ?) RETURNING id", UUID.class, app, "Capability " + a + "." + c));
            }
        }
        String[] types = {"FUNCTIONAL", "NON_FUNCTIONAL", "BUSINESS_RULE", "INTERFACE", "DATA", "REPORT", "SECURITY", "COMPLIANCE"};
        String[] priorities = {"CRITICAL", "HIGH", "MEDIUM", "LOW"};
        jdbc.update("""
            INSERT INTO requirement (key, capability_id, type, status, priority, title, statement, revision, created_at, updated_at)
            SELECT 'RL%1$s-' || n, (?::uuid[])[1 + n %% ?], (?::text[])[1 + n %% 8],
                   CASE WHEN n %% 100 < 25 THEN 'DRAFT' WHEN n %% 100 < 45 THEN 'IN_REVIEW' WHEN n %% 100 < 55 THEN 'REVIEWED'
                        WHEN n %% 100 < 60 THEN 'NEEDS_REVISION' WHEN n %% 100 < 98 THEN 'APPROVED' ELSE 'REJECTED' END,
                   (?::text[])[1 + n %% 4], 'Load requirement ' || n || ' about ' || (ARRAY['login','export','audit','billing','search','report','import','notify'])[1 + n %% 8],
                   'The system shall support behaviour number ' || n || ' for the ' || (ARRAY['login','export','audit','billing','search','report','import','notify'])[1 + n %% 8]
                     || ' function and shall record the outcome so that it can be reviewed.',
                   1, now() - (random() * interval '120 days'), now()
            FROM generate_series(1, ?) AS n
            """.formatted(tag), pgArray(capabilities), capabilities.size(), pgArray(types), pgArray(priorities), count);
        jdbc.update("""
            INSERT INTO acceptance_criterion (requirement_id, ordinal, text)
            SELECT r.id, k, 'Criterion ' || k || ' for ' || r.key
            FROM requirement r JOIN LATERAL generate_series(1, 1 + (abs(hashtext(r.key)) %% 3)) k ON true
            WHERE r.key LIKE 'RL%1$s-%%' AND r.status <> 'DRAFT'
            """.formatted(tag));
        jdbc.update("""
            INSERT INTO requirement_embedding (requirement_id, revision, model, embedding)
            SELECT r.id, 1, 'local-hashing-v1', (SELECT array_agg(random()::real - 0.5) FROM generate_series(1, 1536))::vector
            FROM requirement r WHERE r.key LIKE 'RL%1$s-%%'
            """.formatted(tag));
        List<UUID> ids = jdbc.queryForList("SELECT id FROM requirement WHERE key LIKE ? ORDER BY random() LIMIT 3000", UUID.class, "RL" + tag + "-%");
        return new Seed(capabilities.get(0), capabilities, ids, product);
    }

    private static String pgArray(List<UUID> values) {
        return "{" + String.join(",", values.stream().map(UUID::toString).toList()) + "}";
    }

    private static String pgArray(String[] values) {
        return "{" + String.join(",", values) + "}";
    }

    // ------------------------------------------------------------------ people and phases

    /** {@code writes}: only analysts and architects create and edit requirements; reviewers and approvers read in this scenario. */
    private record Person(String token, boolean writes) {}

    private List<Person> people(int n, Seed seed) {
        // a team of fifteen: six analysts, two architects, four reviewers, two approvers, one viewer
        AccessRole[] roles = {AccessRole.BUSINESS_ANALYST, AccessRole.BUSINESS_ANALYST, AccessRole.BUSINESS_ANALYST, AccessRole.BUSINESS_ANALYST,
            AccessRole.BUSINESS_ANALYST, AccessRole.BUSINESS_ANALYST, AccessRole.ARCHITECT, AccessRole.ARCHITECT, AccessRole.REVIEWER, AccessRole.REVIEWER,
            AccessRole.REVIEWER, AccessRole.REVIEWER, AccessRole.APPROVER, AccessRole.APPROVER, AccessRole.VIEWER};
        List<Person> out = new ArrayList<>();
        String run = UUID.randomUUID().toString().substring(0, 6);
        for (int i = 0; i < n; i++) {
            String sub = "rh-" + run + "-" + i;
            UUID user = users.upsert(sub, sub + "@rehearsal.test", "Rehearsal " + i).getId();
            grants.grant(user, roles[i % roles.length], ScopeType.PRODUCT, seed.productId(), null, user);
            AccessRole role = roles[i % roles.length];
            out.add(new Person(TOKENS.tokenFor(sub, sub + "@rehearsal.test", sub), role == AccessRole.BUSINESS_ANALYST || role == AccessRole.ARCHITECT));
        }
        return out;
    }

    private void runPhase(HttpClient client, Phase phase, Seed seed) throws Exception {
        List<Person> pool = people(phase.people(), seed);
        LoadStats stats = new LoadStats();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(phase.seconds());
        ExecutorService executor = Executors.newFixedThreadPool(phase.people());
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        long started = System.nanoTime();
        for (Person person : pool) futures.add(executor.submit(() -> person(client, person, seed, phase, end, stats)));
        for (var f : futures) f.get();
        executor.shutdown();
        double elapsed = (System.nanoTime() - started) / 1e9;

        say("");
        say(String.format("## Phase %s: %d people, think time %d-%d ms, %.0f s, %,d requests (%.1f per second), %d errors",
            phase.name(), phase.people(), phase.thinkMin(), phase.thinkMax(), elapsed, stats.total(), stats.total() / elapsed, stats.totalErrors()));
        say(String.format("all reads p50 %d ms, p95 %d ms, p99 %d ms; all writes p50 %d ms, p95 %d ms, p99 %d ms",
            stats.percentile(false, 50), stats.percentile(false, 95), stats.percentile(false, 99),
            stats.percentile(true, 50), stats.percentile(true, 95), stats.percentile(true, 99)));
        say(String.format("%-46s %7s %6s %7s %7s %7s %7s", "request", "count", "errors", "p50 ms", "p95 ms", "p99 ms", "max ms"));
        for (var row : stats.rows()) {
            say(String.format("%-46s %7d %6d %7d %7d %7d %7d", row.name(), row.count(), row.errors(), row.p50(), row.p95(), row.p99(), row.max()));
        }
        stats.firstErrors().forEach((k, v) -> say("first error on " + k + ": " + v));
    }

    private void person(HttpClient client, Person person, Seed seed, Phase phase, long endNanos, LoadStats stats) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<String[]> mine = new ArrayList<>();           // [id, revision, capabilityId] of what this person created
        while (System.nanoTime() < endNanos) {
            int pick = rnd.nextInt(100);
            UUID someId = seed.requirementIds().get(rnd.nextInt(seed.requirementIds().size()));
            UUID someCap = seed.capabilityIds().get(rnd.nextInt(seed.capabilityIds().size()));
            if (pick < 18) get(client, person, stats, "GET /requirements (grid, newest first)", "/api/v1/requirements?page=0&size=50&sort=createdAt,desc");
            else if (pick < 28) get(client, person, stats, "GET /requirements?status= (grid, filtered, page 0-3)", "/api/v1/requirements?status=IN_REVIEW&size=50&page=" + rnd.nextInt(4) + "&sort=key,asc");
            else if (pick < 33) get(client, person, stats, "GET /requirements?capabilityId=", "/api/v1/requirements?capabilityId=" + someCap + "&size=50");
            else if (pick < 43) get(client, person, stats, "GET /requirements/{id}", "/api/v1/requirements/" + someId);
            else if (pick < 51) get(client, person, stats, "GET /requirements/{id}/linked", "/api/v1/requirements/" + someId + "/linked");
            else if (pick < 57) get(client, person, stats, "GET /requirements/{id}/acceptance-criteria", "/api/v1/requirements/" + someId + "/acceptance-criteria");
            else if (pick < 63) get(client, person, stats, "GET /search?q=", "/api/v1/search?q=" + new String[] {"login", "export", "audit", "billing"}[rnd.nextInt(4)] + "%20behaviour");
            else if (pick < 69) get(client, person, stats, "GET /tasks/mine", "/api/v1/tasks/mine");
            else if (pick < 74) {
                get(client, person, stats, "GET /products/dashboard (home)", "/api/v1/products/dashboard");
                get(client, person, stats, "GET /requirements?size=1 (home count)", "/api/v1/requirements?size=1");
                get(client, person, stats, "GET /findings?state=OPEN (home count)", "/api/v1/findings?state=OPEN&size=1");
            } else if (pick < 78) get(client, person, stats, "GET /trends?days=90", "/api/v1/trends?days=90");
            else if (pick < 81) {
                get(client, person, stats, "GET /boards/templates", "/api/v1/boards/templates");
                get(client, person, stats, "GET /requirements?status=APPROVED (board column)", "/api/v1/requirements?status=APPROVED&size=50&sort=key,asc");
            } else if (pick < 83) get(client, person, stats, "GET /me", "/api/v1/me");
            else if (!person.writes()) get(client, person, stats, "GET /requirements (grid, newest first)", "/api/v1/requirements?page=0&size=50&sort=createdAt,desc");
            else if (pick < 88 || mine.isEmpty()) {
                String body = "{\"title\":\"Created under load " + rnd.nextInt(1_000_000) + "\",\"statement\":\"The system shall do the thing number " + rnd.nextInt(1_000_000)
                    + " and report its result.\",\"type\":\"FUNCTIONAL\",\"priority\":\"MEDIUM\",\"capabilityId\":\"" + someCap + "\"}";
                String res = send(client, person, stats, "POST /requirements (create)", "POST", "/api/v1/requirements", body);
                String id = first(ID, res), rev = first(REVISION, res);
                if (id != null) mine.add(new String[] {id, rev == null ? "1" : rev, someCap.toString()});
            } else {
                String[] item = mine.get(rnd.nextInt(mine.size()));
                int w = rnd.nextInt(3);
                if (w == 0) {
                    String body = "{\"revision\":" + item[1] + ",\"title\":\"Edited under load " + rnd.nextInt(1_000_000) + "\",\"statement\":\"The system shall do the edited thing and report it.\",\"type\":\"FUNCTIONAL\",\"priority\":\"HIGH\",\"capabilityId\":\"" + item[2] + "\"}";
                    String res = send(client, person, stats, "PATCH /requirements/{id} (edit)", "PATCH", "/api/v1/requirements/" + item[0], body);
                    String rev = first(REVISION, res);
                    if (rev != null) item[1] = rev;
                } else if (w == 1) {
                    send(client, person, stats, "POST /requirements/{id}/acceptance-criteria", "POST", "/api/v1/requirements/" + item[0] + "/acceptance-criteria", "{\"text\":\"It reports its result\"}");
                } else {
                    String res = send(client, person, stats, "POST /requirements/{id}/transition (submit)", "POST",
                        "/api/v1/requirements/" + item[0] + "/transition?revision=" + item[1], "{\"target\":\"IN_REVIEW\",\"reason\":\"rehearsal\"}");
                    String rev = first(REVISION, res);
                    if (rev != null) item[1] = rev;
                }
            }
            try {
                Thread.sleep(phase.thinkMin() + rnd.nextInt(Math.max(1, phase.thinkMax() - phase.thinkMin())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static String first(Pattern p, String text) {
        if (text == null) return null;
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private void get(HttpClient client, Person person, LoadStats stats, String name, String path) {
        send(client, person, stats, name, "GET", path, null);
    }

    private String send(HttpClient client, Person person, LoadStats stats, String name, String method, String path, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + person.token()).header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        long t0 = System.nanoTime();
        try {
            HttpResponse<String> res = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            boolean ok = res.statusCode() < 400;
            stats.record(name, System.nanoTime() - t0, ok, ok ? "" : res.statusCode() + " " + res.body().substring(0, Math.min(700, res.body().length())));
            return res.body();
        } catch (Exception e) {
            stats.record(name, System.nanoTime() - t0, false, e.toString());
            return null;
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
