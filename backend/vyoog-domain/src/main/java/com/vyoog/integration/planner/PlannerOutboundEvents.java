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
 * {@code event_id}, an {@code occurred_at}, version-based ordering): the Planner has not defined the bodies, and nobody there has
 * confirmed these.
 */
@Service
public class PlannerOutboundEvents {

    public static final String STATUS_CHANGED = "requirement.status_changed";

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
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT r.key, l.source_function_id FROM planner_function_link l JOIN requirement r ON r.id = l.requirement_id
             WHERE l.requirement_id = ?""", requirementId);
        if (rows.isEmpty()) return;
        UUID eventId = UUID.randomUUID();
        long version = jdbc.queryForObject("SELECT nextval('planner_outbound_event_version')", Long.class);
        String payload = write(statusChangedPayload(eventId, Instant.now(), (String) rows.get(0).get("key"),
            (String) rows.get(0).get("source_function_id"), from, to, version));
        jdbc.update("INSERT INTO planner_outbound_event (event_id, version, event_type, requirement_id, payload) VALUES (?, ?, ?, ?, ?::jsonb)",
            eventId, version, STATUS_CHANGED, requirementId, payload);
    }

    /** The body of {@code requirement.status_changed}. Statuses are lower case, as in the Planner's own example ({@code "draft"}). */
    static Map<String, Object> statusChangedPayload(UUID eventId, Instant occurredAt, String requirementKey, String sourceFunctionId,
                                                    String from, String to, long version) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("event", STATUS_CHANGED);
        p.put("event_id", eventId.toString());
        p.put("occurred_at", occurredAt.toString());
        p.put("version", version);
        p.put("requirement_id", requirementKey);
        p.put("source_function_id", sourceFunctionId);
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
