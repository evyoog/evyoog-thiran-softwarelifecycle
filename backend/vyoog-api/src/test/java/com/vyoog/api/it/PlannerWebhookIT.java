package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vyoog.integration.IntegrationService;
import com.vyoog.integration.WebhookSignatureVerifier;
import com.vyoog.integration.planner.PlannerConnector;
import com.vyoog.integration.planner.PlannerWebhookDelivery;
import com.vyoog.requirements.BulkEditService;
import com.vyoog.requirements.Requirement;
import com.vyoog.requirements.RequirementStatus;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * VYB-0918a (D24, D52): telling the Agile Planner back, over real HTTP to a stub Planner in this JVM and a real PostgreSQL. A change to
 * the status of a requirement the Planner sent is queued with the change, and sent signed (its own {@code X-ALM-Signature}) to its
 * webhook; nothing is sent for any other requirement, a failed send waits and is tried again, and one requirement's changes go out in
 * the order they happened.
 *
 * <p>The scheduled job runs in this application too; each test takes its lease so that only the passes the test asks for happen, and
 * asserts on what ends up in the database.
 */
class PlannerWebhookIT extends IntegrationTestBase {

    @Autowired IntegrationService integrations;
    @Autowired PlannerWebhookDelivery delivery;
    @Autowired BulkEditService bulk;
    @Autowired com.vyoog.design.DesignService design;
    @Autowired com.vyoog.evidence.TestCaseService testCases;
    @Autowired com.vyoog.evidence.VerificationService verification;
    @Autowired ObjectMapper json;

    private static final String SECRET = "planner-shared-secret";

    record Received(String method, String path, Map<String, String> headers, String body) {
        String header(String name) { return headers.get(name.toLowerCase()); }
    }

    private HttpServer server;
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private volatile int answer = 200;

    @BeforeEach
    void startStub() throws IOException {
        jdbc.update("DELETE FROM planner_outbound_event"); // the queue is global: another test's waiting events must not be sent (or counted) here
        // The application's own 30-second job runs in this context too. Hold its lease for ten minutes so that only the passes these
        // tests ask for happen: a pass of its own between a test's pass and its assertions would change what they count.
        jdbc.update("""
            INSERT INTO scheduler_lock (name, locked_by, locked_at, locked_until) VALUES ('planner-webhooks', 'PlannerWebhookIT', now(), now() + interval '10 minutes')
            ON CONFLICT (name) DO UPDATE SET locked_by = EXCLUDED.locked_by, locked_at = EXCLUDED.locked_at, locked_until = EXCLUDED.locked_until""");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    @AfterEach
    void stopStubAndDisconnect() {
        server.stop(0);
        // the scheduled job shares this connection: leave it unconfigured so it never sends another test's events to a dead stub
        jdbc.update("UPDATE integration_connection SET config = NULL, webhook_secret = NULL, connected = false, degraded = false, failure_count = 0 WHERE key = ?", PlannerConnector.KEY);
    }

    private void handle(HttpExchange ex) throws IOException {
        Map<String, String> headers = new HashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
        received.add(new Received(ex.getRequestMethod(), ex.getRequestURI().getPath(), headers,
            new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        ex.sendResponseHeaders(answer, -1);
        ex.close();
    }

    private void connect() {
        integrations.setConfig(PlannerConnector.KEY, "{\"baseUrl\":\"http://127.0.0.1:" + server.getAddress().getPort() + "\"}");
        integrations.setConnected(PlannerConnector.KEY, true, SECRET);
    }

    /** A requirement the Planner sent: a real one with the back-reference its intake writes. */
    private Requirement fromPlanner(Portfolio p, UUID author) {
        Requirement r = newRequirement(p, author);
        jdbc.update("INSERT INTO planner_function_link (source_function_id, requirement_id, content_hash) VALUES (?, ?, 'x')", unique("FN"), r.getId());
        return r;
    }

    private Requirement submit(Requirement r, UUID author) {
        return requirementService.transition(r.getId(), r.getRevision(), RequirementStatus.IN_REVIEW, "no criteria yet, submitted on purpose", author);
    }

    private List<Map<String, Object>> events(Requirement r) {
        return jdbc.queryForList("SELECT id, event_id, version, state, attempts, last_error, next_attempt_at, payload::text AS payload "
            + "FROM planner_outbound_event WHERE requirement_id = ? ORDER BY version", r.getId());
    }

    private List<Received> mine(String eventId) {
        synchronized (received) {
            return received.stream().filter(x -> x.body().contains(eventId)).toList();
        }
    }

    @Test
    void VYB0918_AC1_aStatusChangeIsQueuedWithTheChangeAndSentSignedToThePlannersWebhook() throws Exception {
        UUID author = newUser("author");
        Requirement r = fromPlanner(newPortfolio(), author);
        submit(r, author);

        List<Map<String, Object>> queued = events(r);
        assertThat(queued).hasSize(1);
        assertThat(queued.get(0).get("state")).isIn("PENDING", "SENT"); // the scheduled job may already have it if connected; it is not yet
        String eventId = queued.get(0).get("event_id").toString();

        connect();
        delivery.deliver();

        assertThat(events(r).get(0).get("state")).isEqualTo("SENT");
        List<Received> sent = mine(eventId);
        assertThat(sent).hasSize(1);
        Received call = sent.get(0);
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.path()).isEqualTo("/webhooks/alm");
        assertThat(call.header("Content-Type")).isEqualTo("application/json");
        assertThat(call.header("Idempotency-Key")).isEqualTo("planner-event:" + eventId);
        assertThat(call.header("X-ALM-Signature")).as("the Planner's own scheme, over the exact bytes sent")
            .isEqualTo("hmac-sha256=" + WebhookSignatureVerifier.sign(SECRET, call.body()));
        assertThat(call.header("X-Vyoog-Signature")).isNull();
        JsonNode body = json.readTree(call.body());
        assertThat(body.get("event").asText()).isEqualTo("requirement.status_changed");
        assertThat(body.get("event_id").asText()).isEqualTo(eventId);
        assertThat(body.get("requirement_id").asText()).isEqualTo(r.getKey());
        assertThat(body.get("source_function_id").asText()).startsWith("FN-");
        assertThat(body.get("status").asText()).isEqualTo("in_review");
        assertThat(body.get("previous_status").asText()).isEqualTo("draft");
        assertThat(body.get("version").asLong()).isEqualTo(((Number) queued.get(0).get("version")).longValue());
        assertThat(body.get("occurred_at").asText()).isNotBlank();
    }

    @Test
    void VYB0918_AC2_aRequirementThePlannerDidNotSendWritesNothingAndARolledBackChangeSendsNothing() {
        UUID author = newUser("author");
        Requirement ours = newRequirement(newPortfolio(), author);
        submit(ours, author);
        assertThat(events(ours)).isEmpty();

        Requirement theirs = fromPlanner(newPortfolio(), author);
        assertThatThrownBy(() -> requirementService.transition(theirs.getId(), theirs.getRevision(), RequirementStatus.APPROVED, null, author))
            .as("draft cannot jump to approved").isInstanceOf(RuntimeException.class);
        assertThat(events(theirs)).as("a refused change tells the Planner nothing").isEmpty();
    }

    @Test
    void VYB0918_AC3_aBulkStatusChangeIsReportedToo() {
        UUID author = newUser("author");
        Requirement r = fromPlanner(newPortfolio(), author);
        var result = bulk.apply(List.of(r.getId()), new BulkEditService.Changes(RequirementStatus.IN_REVIEW, null, null, false, null, null,
            "no criteria yet, on purpose"), author);
        assertThat(result.outcomes().get(0).applied()).isTrue();
        assertThat(events(r)).hasSize(1);
        assertThat(events(r).get(0).get("payload").toString()).contains("\"status\": \"in_review\"");
    }

    @Test
    void VYB0918_AC4_aFailedSendWaitsAndIsTriedAgainWithTheSameEventAndKey() {
        UUID author = newUser("author");
        Requirement r = fromPlanner(newPortfolio(), author);
        submit(r, author);
        String eventId = events(r).get(0).get("event_id").toString();
        answer = 500;
        connect();

        delivery.deliver();
        Map<String, Object> failed = events(r).get(0);
        assertThat(failed.get("state")).isEqualTo("PENDING");
        assertThat((Integer) failed.get("attempts")).isEqualTo(1);
        assertThat(failed.get("last_error").toString()).contains("HTTP 500");
        assertThat(((Timestamp) failed.get("next_attempt_at")).toInstant()).isAfter(Instant.now().plus(30, ChronoUnit.SECONDS));

        int calls = mine(eventId).size();
        delivery.deliver(); // not due yet
        assertThat(mine(eventId)).as("nothing is sent before it is due").hasSize(calls);

        answer = 200;
        jdbc.update("UPDATE planner_outbound_event SET next_attempt_at = now() WHERE event_id = ?::uuid", eventId);
        delivery.deliver();
        assertThat(events(r).get(0).get("state")).isEqualTo("SENT");
        assertThat((Integer) events(r).get(0).get("attempts")).isEqualTo(2);
        assertThat(mine(eventId).stream().map(x -> x.header("Idempotency-Key")).distinct()).containsExactly("planner-event:" + eventId);
        assertThat(mine(eventId).stream().map(Received::body).distinct()).as("the same body every time").hasSize(1);
    }

    @Test
    void VYB0918_AC5_aLaterChangeWaitsForAnEarlierOneOfTheSameRequirementAndTheyGoInOrder() {
        UUID author = newUser("author");
        UUID admin = newAdministrator();
        Requirement r = fromPlanner(newPortfolio(), author);
        r = submit(r, author);
        requirementService.transition(r.getId(), r.getRevision(), RequirementStatus.REVIEWED, null, admin);
        List<Map<String, Object>> two = events(r);
        assertThat(two).hasSize(2);
        String first = two.get(0).get("event_id").toString();
        String second = two.get(1).get("event_id").toString();
        assertThat(((Number) two.get(0).get("version")).longValue()).isLessThan(((Number) two.get(1).get("version")).longValue());

        answer = 500;
        connect();
        delivery.deliver();
        assertThat(mine(first)).isNotEmpty();
        assertThat(mine(second)).as("the second is not tried while the first has just failed").isEmpty();

        answer = 200;
        delivery.deliver(); // the first is waiting out its delay: the second must not overtake it
        assertThat(mine(second)).isEmpty();
        assertThat(events(r).get(1).get("state")).isEqualTo("PENDING");

        jdbc.update("UPDATE planner_outbound_event SET next_attempt_at = now() WHERE event_id = ?::uuid", first);
        delivery.deliver();
        assertThat(events(r).stream().map(e -> e.get("state"))).containsExactly("SENT", "SENT");
        List<String> order;
        synchronized (received) {
            order = received.stream().map(Received::body).filter(b -> b.contains(first) || b.contains(second))
                .map(b -> b.contains(first) ? "first" : "second").toList();
        }
        assertThat(order.get(order.size() - 2)).isEqualTo("first");
        assertThat(order.get(order.size() - 1)).isEqualTo("second");
    }

    @Test
    void VYB0918_AC6_afterFiveFailedAttemptsTheEventIsLeftFailedWithItsReason() {
        UUID author = newUser("author");
        Requirement r = fromPlanner(newPortfolio(), author);
        submit(r, author);
        jdbc.update("UPDATE planner_outbound_event SET attempts = 4 WHERE requirement_id = ?", r.getId());
        answer = 503;
        connect();
        delivery.deliver();
        Map<String, Object> e = events(r).get(0);
        assertThat(e.get("state")).isEqualTo("FAILED");
        assertThat((Integer) e.get("attempts")).isEqualTo(5);
        assertThat(e.get("last_error").toString()).contains("HTTP 503");
    }

    @Test
    void VYB0918_AC7_whileTheConnectionIsNotSetUpEventsWaitAndGoOutOnceItIs() {
        UUID author = newUser("author");
        Requirement r = fromPlanner(newPortfolio(), author);
        submit(r, author);
        String eventId = events(r).get(0).get("event_id").toString();

        assertThat(delivery.deliver().connectionReady()).isFalse();
        assertThat(events(r).get(0).get("state")).as("waiting, not failed").isEqualTo("PENDING");
        assertThat(mine(eventId)).isEmpty();

        connect();
        delivery.deliver();
        assertThat(events(r).get(0).get("state")).isEqualTo("SENT");
        assertThat(mine(eventId)).hasSize(1);
    }

    @Test
    void VYB0918_AC8_anEventThatWaitedFourteenDaysForASetupThatNeverCameIsFailedAndOldDoneOnesArePurged() {
        UUID author = newUser("author");
        Requirement r = fromPlanner(newPortfolio(), author);
        submit(r, author);
        requirementService.transition(r.getId(), jdbc.queryForObject("SELECT revision FROM requirement WHERE id = ?", Integer.class, r.getId()),
            RequirementStatus.REVIEWED, null, newAdministrator());
        jdbc.update("UPDATE planner_outbound_event SET created_at = now() - interval '15 days' WHERE requirement_id = ? AND version = "
            + "(SELECT min(version) FROM planner_outbound_event WHERE requirement_id = ?)", r.getId(), r.getId());

        delivery.deliver(); // not set up
        assertThat(events(r).stream().map(e -> e.get("state"))).containsExactly("FAILED", "PENDING");
        assertThat(events(r).get(0).get("last_error").toString()).contains("not set up");

        jdbc.update("UPDATE planner_outbound_event SET created_at = now() - interval '31 days', state = 'SENT' WHERE requirement_id = ? AND state = 'FAILED'", r.getId());
        assertThat(delivery.purgeOld()).isGreaterThanOrEqualTo(1);
        assertThat(events(r)).as("the waiting one is never purged").hasSize(1);
    }

    @Test
    void VYB0918_AC9_aPassStopsAfterThreeFailuresInARowBecauseThePlannerIsEvidentlyDown() {
        UUID author = newUser("author");
        List<Requirement> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Requirement r = fromPlanner(newPortfolio(), author);
            submit(r, author);
            five.add(r);
        }
        answer = 500;
        connect();
        delivery.deliver();

        long tried = five.stream().filter(r -> (Integer) events(r).get(0).get("attempts") > 0).count();
        assertThat(tried).as("three were tried; the other two wait for the next pass").isEqualTo(3);

        answer = 200;
        jdbc.update("UPDATE planner_outbound_event SET next_attempt_at = now()");
        delivery.deliver();
        assertThat(five.stream().map(r -> events(r).get(0).get("state"))).containsOnly("SENT");
    }

    @Test
    void VYB0918_AC15_theDesignTestCaseAndResultEventsGoOutSignedToThePlannersWebhookInOrder() throws Exception {
        UUID author = newUser("author");
        Portfolio p = newPortfolio();
        Requirement r = submit(fromPlanner(p, author), author);
        design.generateFrom(design.createFlow(p.applicationId(), author).getId(), p.applicationId(), author);
        var t = testCases.draft("Pausing stops billing", null, null, r.getId(), author);
        verification.ingest(verification.findOrCreateRun(unique("build"), "ci"),
            new com.vyoog.evidence.VerificationService.ResultInput(t.getKey(), null, com.vyoog.evidence.VerificationResult.PASS, List.of(r.getKey())));
        connect();
        delivery.deliver();

        List<Map<String, Object>> queued = events(r);
        assertThat(queued).hasSize(4).allSatisfy(e -> assertThat(e.get("state")).isEqualTo("SENT"));
        List<Received> sent;
        synchronized (received) {
            sent = List.copyOf(received);
        }
        List<String> types = new ArrayList<>();
        long last = 0;
        for (Received call : sent) {
            assertThat(call.header("X-ALM-Signature")).isEqualTo("hmac-sha256=" + WebhookSignatureVerifier.sign(SECRET, call.body()));
            JsonNode b = json.readTree(call.body());
            types.add(b.get("event").asText());
            assertThat(b.get("version").asLong()).as("sent in the order they happened").isGreaterThan(last);
            last = b.get("version").asLong();
        }
        assertThat(types).containsExactly("requirement.status_changed", "design_artifact.created", "test_case.created", "test_result.recorded");
    }
}
