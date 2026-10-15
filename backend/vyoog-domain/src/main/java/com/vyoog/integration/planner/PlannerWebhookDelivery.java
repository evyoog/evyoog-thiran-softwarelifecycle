package com.vyoog.integration.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.integration.IntegrationConnection;
import com.vyoog.integration.IntegrationService;
import com.vyoog.integration.connector.ConnectorConfig;
import com.vyoog.integration.connector.ConnectorExecutor;
import com.vyoog.integration.connector.ConnectorNotConfiguredException;
import com.vyoog.integration.connector.ConnectorResult;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0918a (D24, D52): sends what {@link PlannerOutboundEvents} wrote to the Agile Planner, through the connector framework (signature,
 * retries inside one send, idempotency key, sync log, health). Run every 30 seconds by {@code ScheduledJobs} behind a lock.
 *
 * <ul>
 *   <li>An event that fails is tried again after 1, 2, 4 and 8 minutes, then left FAILED. A later event about the same requirement waits
 *       for an earlier one that is still waiting, so the Planner sees one requirement's changes in the order they happened (it also
 *       ignores an older version, but there is no reason to rely on that).</li>
 *   <li>While the connection is not set up (no URL or secret), events wait rather than fail, so they are sent once it is; one still
 *       waiting after 14 days is marked FAILED. Sent and failed events are removed after 30 days.</li>
 *   <li>One pass sends at most 25 events and stops after about 25 seconds.</li>
 * </ul>
 */
@Service
public class PlannerWebhookDelivery {

    private static final Logger log = LoggerFactory.getLogger(PlannerWebhookDelivery.class);

    static final int BATCH = 25;
    static final Duration BUDGET = Duration.ofSeconds(25);
    static final int MAX_ATTEMPTS = 5;
    static final Duration WAIT_FOR_SETUP = Duration.ofDays(14);
    static final Duration KEEP = Duration.ofDays(30);

    /** What one pass did. */
    public record Result(int sent, int failedForNow, int givenUp, boolean connectionReady) {}

    private final JdbcTemplate jdbc;
    private final IntegrationService integrations;
    private final ConnectorExecutor executor;
    private final PlannerConnector connector;
    private final ObjectMapper json;

    public PlannerWebhookDelivery(JdbcTemplate jdbc, IntegrationService integrations, ConnectorExecutor executor,
                                  PlannerConnector connector, ObjectMapper json) {
        this.jdbc = jdbc;
        this.integrations = integrations;
        this.executor = executor;
        this.connector = connector;
        this.json = json;
    }

    /** Minutes to wait after the Nth failed attempt: 1, 2, 4, 8. */
    static Duration backoffAfter(int attempts) {
        return Duration.ofMinutes(1L << Math.min(Math.max(attempts - 1, 0), 3));
    }

    /** One pass at a time in this process (the scheduled job and a test never both pick up the same event). */
    public synchronized Result deliver() {
        if (!ready()) {
            int gaveUp = jdbc.update("""
                UPDATE planner_outbound_event SET state = 'FAILED', last_error = 'the Agile Planner connection was not set up in time'
                 WHERE state = 'PENDING' AND created_at < ?""", java.sql.Timestamp.from(Instant.now().minus(WAIT_FOR_SETUP)));
            return new Result(0, 0, gaveUp, false);
        }
        long deadline = System.nanoTime() + BUDGET.toNanos();
        int sent = 0;
        int failed = 0;
        int gaveUp = 0;
        Set<UUID> blocked = new HashSet<>(); // a requirement whose earlier event just failed: its later ones wait for the next pass
        for (Map<String, Object> row : due()) {
            if (System.nanoTime() > deadline) break;
            UUID requirementId = (UUID) row.get("requirement_id");
            if (blocked.contains(requirementId)) continue;
            UUID id = (UUID) row.get("id");
            int attempts = (Integer) row.get("attempts");
            try {
                ConnectorResult result = executor.execute(connector.operation((UUID) row.get("event_id"), (String) row.get("payload")));
                if (result.succeeded()) {
                    jdbc.update("UPDATE planner_outbound_event SET state = 'SENT', sent_at = now(), attempts = ?, last_error = NULL WHERE id = ?", attempts + 1, id);
                    sent++;
                } else if (result.outcome() == ConnectorResult.Outcome.IN_PROGRESS_ELSEWHERE) {
                    blocked.add(requirementId); // another instance has it; leave it
                } else if (attempts + 1 >= MAX_ATTEMPTS) {
                    jdbc.update("UPDATE planner_outbound_event SET state = 'FAILED', attempts = ?, last_error = ? WHERE id = ?", attempts + 1, result.error(), id);
                    gaveUp++;
                    blocked.add(requirementId);
                } else {
                    jdbc.update("UPDATE planner_outbound_event SET attempts = ?, last_error = ?, next_attempt_at = ? WHERE id = ?",
                        attempts + 1, result.error(), java.sql.Timestamp.from(Instant.now().plus(backoffAfter(attempts + 1))), id);
                    failed++;
                    blocked.add(requirementId);
                }
            } catch (ConnectorNotConfiguredException notReady) {
                return new Result(sent, failed, gaveUp, false); // the connection was changed under us; the rest wait
            }
        }
        if (sent + failed + gaveUp > 0) log.info("[planner] webhooks: {} sent, {} to retry, {} given up", sent, failed, gaveUp);
        return new Result(sent, failed, gaveUp, true);
    }

    /** Events due now, oldest version first, each only if no earlier event about the same requirement is still waiting out a delay. */
    private List<Map<String, Object>> due() {
        return jdbc.queryForList("""
            SELECT e.id, e.event_id, e.requirement_id, e.payload::text AS payload, e.attempts
              FROM planner_outbound_event e
             WHERE e.state = 'PENDING' AND e.next_attempt_at <= now()
               AND NOT EXISTS (SELECT 1 FROM planner_outbound_event o
                                WHERE o.requirement_id = e.requirement_id AND o.state = 'PENDING' AND o.version < e.version
                                  AND o.next_attempt_at > now())
             ORDER BY e.version LIMIT ?""", BATCH);
    }

    private boolean ready() {
        try {
            IntegrationConnection connection = integrations.get(PlannerConnector.KEY);
            return ConnectorConfig.problem(connection, json, connector).isEmpty();
        } catch (java.util.NoSuchElementException missing) {
            return false;
        }
    }

    /** Nightly: sent and failed events older than 30 days go. Waiting ones are never purged. */
    public int purgeOld() {
        return jdbc.update("DELETE FROM planner_outbound_event WHERE state IN ('SENT', 'FAILED') AND created_at < ?",
            java.sql.Timestamp.from(Instant.now().minus(KEEP)));
    }
}
