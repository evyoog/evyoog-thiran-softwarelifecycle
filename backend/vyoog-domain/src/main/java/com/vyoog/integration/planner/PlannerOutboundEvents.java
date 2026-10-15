package com.vyoog.integration.planner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0918a (D24, D52): writes what the Agile Planner is to be told, in the same transaction as the change that caused it. Only a
 * requirement the Planner sent (one with a {@code planner_function_link}) is ever reported; every other requirement does nothing here.
 * Sending is {@link PlannerWebhookDelivery}'s job, later and on its own, so a Planner that is down never holds up or fails a change.
 *
 * <p>The payload shapes are this application's proposal built from what the Planner's documents say (an {@code event}, an
 * {@code event_id}, an {@code occurred_at}, version-based ordering, and for a result its outcome and build reference): the Planner has
 * not defined the bodies, and nobody there has confirmed these. See {@code docs/09-integrations/agile-planner-webhooks.md}.
 */
@Service
public class PlannerOutboundEvents {

    public static final String STATUS_CHANGED = "requirement.status_changed";
    public static final String DESIGN_ARTIFACT_CREATED = "design_artifact.created";
    public static final String TEST_CASE_CREATED = "test_case.created";
    public static final String TEST_RESULT_RECORDED = "test_result.recorded";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PlannerOutboundEvents(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** A requirement moved from one status to another. Nothing is written for a requirement the Planner did not send. */
    @Transactional
    public void requirementStatusChanged(UUID requirementId, String from, String to) {
        if (to == null || to.equals(from)) return;
        enqueue(STATUS_CHANGED, requirementId, null, (eventId, occurredAt, version, source) ->
            statusChangedPayload(eventId, occurredAt, source.key(), source.functionId(), from, to, version));
    }

    /**
     * A design artifact (a node on the application's design) now covers the requirement. Reported once per node and requirement, whichever
     * way it came to cover it. The two pipeline milestones ("Testing", "Deployment") are derived from the requirements and are not
     * design artifacts, so they are not reported.
     */
    @Transactional
    public void designArtifactCreated(UUID requirementId, UUID nodeId, String kind, String name) {
        if (kind != null && (kind.equalsIgnoreCase("testing") || kind.equalsIgnoreCase("deployment"))) return;
        enqueue(DESIGN_ARTIFACT_CREATED, requirementId, "design_artifact:" + nodeId + ":" + requirementId, (eventId, occurredAt, version, source) -> {
            Map<String, Object> p = envelope(DESIGN_ARTIFACT_CREATED, eventId, occurredAt, version, source);
            p.put("design_artifact_id", nodeId.toString());
            p.put("kind", kind == null ? null : kind.toLowerCase(Locale.ROOT));
            p.put("name", name);
            return p;
        });
    }

    /** As {@link #testCaseLinked(UUID, UUID, String, String)}, reading the test case's key and title (it must already be saved and flushed). */
    @Transactional
    public void testCaseLinked(UUID requirementId, UUID testCaseId) {
        if (sourceOf(requirementId) == null) return;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT key, title FROM test_case WHERE id = ?", testCaseId);
        if (rows.isEmpty()) return;
        testCaseLinked(requirementId, testCaseId, (String) rows.get(0).get("key"), (String) rows.get(0).get("title"));
    }

    /** A test case now verifies the requirement. Reported once per test case and requirement, whichever way it came to. */
    @Transactional
    public void testCaseLinked(UUID requirementId, UUID testCaseId, String key, String title) {
        enqueue(TEST_CASE_CREATED, requirementId, "test_case:" + testCaseId + ":" + requirementId, (eventId, occurredAt, version, source) -> {
            Map<String, Object> p = envelope(TEST_CASE_CREATED, eventId, occurredAt, version, source);
            p.put("test_case_id", key);
            p.put("title", title);
            return p;
        });
    }

    /**
     * A test result was recorded against the requirement: {@code outcome} is {@code pass}, {@code fail} or {@code blocked};
     * {@code buildReference} says which build or run it was. {@code runIdentity} says which run, so a result is reported once per run,
     * test case and requirement. The revision of the requirement the test was run against is carried so the Planner can tell a result for
     * an older wording from one for the current.
     */
    @Transactional
    public void testResultRecorded(UUID requirementId, String testCaseKey, String outcome, String buildReference, int requirementRevision,
                                   String runIdentity) {
        enqueue(TEST_RESULT_RECORDED, requirementId, "test_result:" + runIdentity + ":" + testCaseKey + ":" + requirementId,
            (eventId, occurredAt, version, source) -> {
                Map<String, Object> p = envelope(TEST_RESULT_RECORDED, eventId, occurredAt, version, source);
                p.put("test_case_id", testCaseKey);
                p.put("outcome", outcome.toLowerCase(Locale.ROOT));
                p.put("build_reference", buildReference);
                p.put("requirement_revision", requirementRevision);
                return p;
            });
    }

    /** What the payload builders are given: the event's identity and ordering, and which Function the requirement came from. */
    private interface PayloadBuilder {
        Map<String, Object> build(UUID eventId, Instant occurredAt, long version, Source source);
    }

    private record Source(String key, String functionId) {}

    private Source sourceOf(UUID requirementId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT r.key, l.source_function_id FROM planner_function_link l JOIN requirement r ON r.id = l.requirement_id
             WHERE l.requirement_id = ?""", requirementId);
        return rows.isEmpty() ? null : new Source((String) rows.get(0).get("key"), (String) rows.get(0).get("source_function_id"));
    }

    /** Writes one event, in the caller's transaction, unless the requirement did not come from the Planner or the same thing was already queued. */
    private void enqueue(String type, UUID requirementId, String dedupeKey, PayloadBuilder builder) {
        Source source = sourceOf(requirementId);
        if (source == null) return;
        if (dedupeKey != null && Boolean.TRUE.equals(
            jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM planner_outbound_event WHERE dedupe_key = ?)", Boolean.class, dedupeKey))) {
            return;
        }
        UUID eventId = UUID.randomUUID();
        long version = jdbc.queryForObject("SELECT nextval('planner_outbound_event_version')", Long.class);
        String payload = write(builder.build(eventId, Instant.now(), version, source));
        jdbc.update("INSERT INTO planner_outbound_event (event_id, version, event_type, requirement_id, payload, dedupe_key) "
            + "VALUES (?, ?, ?, ?, ?::jsonb, ?) ON CONFLICT (dedupe_key) DO NOTHING",
            eventId, version, type, requirementId, payload, dedupeKey);
    }

    /** The fields every event carries; an event adds its own after them. */
    static Map<String, Object> envelope(String type, UUID eventId, Instant occurredAt, long version, Source source) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("event", type);
        p.put("event_id", eventId.toString());
        p.put("occurred_at", occurredAt.toString());
        p.put("version", version);
        p.put("requirement_id", source.key());
        p.put("source_function_id", source.functionId());
        return p;
    }

    /** The body of {@code requirement.status_changed}. Statuses are lower case, as in the Planner's own example ({@code "draft"}). */
    static Map<String, Object> statusChangedPayload(UUID eventId, Instant occurredAt, String requirementKey, String sourceFunctionId,
                                                    String from, String to, long version) {
        Map<String, Object> p = envelope(STATUS_CHANGED, eventId, occurredAt, version, new Source(requirementKey, sourceFunctionId));
        p.put("status", to.toLowerCase(Locale.ROOT));
        p.put("previous_status", from == null ? null : from.toLowerCase(Locale.ROOT));
        return p;
    }

    private String write(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not write the event", e);
        }
    }
}
