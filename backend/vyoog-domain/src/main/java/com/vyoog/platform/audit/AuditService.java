package com.vyoog.platform.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * One place that writes {@code audit_event} rows, so "an audit event for every state
 * change" (CLAUDE.md's Definition of Done) is one call, not a rule every service has
 * to remember to reimplement.
 *
 * <p>Writes go through raw JdbcTemplate rather than {@code AuditEventRepository.save}
 * (VYB-0721): the {@code ip} column is Postgres {@code inet}, which needs a {@code
 * ?::inet} cast Hibernate's plain insert wouldn't produce — the same reasoning that
 * already routes pgvector's {@code embedding} column through JdbcTemplate. {@link
 * AuditEvent} stays a real JPA entity for reads ({@code AuditQueryService}).
 */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RequestContext requestContext;

    public AuditService(JdbcTemplate jdbc, ObjectMapper json, RequestContext requestContext) {
        this.jdbc = jdbc;
        this.json = json;
        this.requestContext = requestContext;
    }

    public void record(UUID actorId, String action, String objectType, UUID objectId,
                        Map<String, ?> before, Map<String, ?> after) {
        record(actorId, "USER", action, objectType, objectId, before, after);
    }

    /**
     * An audit event that must survive the caller's transaction rolling back: for a refusal, where
     * the caller records what was refused and then throws. With {@link #record} the event would be
     * rolled back along with everything else and the refusal would leave no trace. Runs in its own
     * transaction, so it must be called through the Spring proxy (from another bean).
     */
    @org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordIndependently(UUID actorId, String action, String objectType, UUID objectId,
                                     Map<String, ?> before, Map<String, ?> after) {
        record(actorId, "USER", action, objectType, objectId, before, after);
    }

    /** VYB-0313: something the system did, not a person — no actor id. */
    public void recordSystem(String action, String objectType, UUID objectId, Map<String, ?> after) {
        record(null, "SYSTEM", action, objectType, objectId, null, after);
    }

    /** VYB-0311/0316: something a CI/service-account caller did. */
    public void recordService(String action, String objectType, UUID objectId, Map<String, ?> after) {
        record(null, "SERVICE", action, objectType, objectId, null, after);
    }

    /**
     * VYB-0125/0720 (session 14 fix): every call site here — every audited write in
     * the whole application — had never actually run against a real Postgres until
     * this session (unit tests mock {@code AuditService} entirely). The very first
     * real invocation failed: {@code jdbc.update}'s varargs form passes a plain
     * {@code java.time.Instant} straight to pgjdbc's 2-arg {@code setObject}, which
     * can't infer a SQL type for it ({@code PSQLException: Can't infer the SQL type
     * to use for an instance of java.time.Instant}) — {@code Timestamp}, unlike
     * {@code Instant}, pgjdbc maps directly. This was never a "sometimes" bug: every
     * single audited action anywhere in this codebase would have hit it the moment
     * any of them ran for real.
     */
    public void record(UUID actorId, String actorType, String action, String objectType, UUID objectId,
                        Map<String, ?> before, Map<String, ?> after) {
        String ip = requestContext.remoteAddress().orElse(null);
        String requestId = requestContext.requestId().orElse(null);
        jdbc.update("""
            INSERT INTO audit_event
                (id, occurred_at, actor_id, actor_type, action, object_type, object_id, before, after,
                 request_id, ip)
            VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?::inet)
            """,
            Timestamp.from(Instant.now()), actorId, actorType, action, objectType, objectId,
            writeOrNull(before), writeOrNull(after), requestId, ip);
    }

    private String writeOrNull(Map<String, ?> value) {
        if (value == null) return null;
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            // Auditing must never be why the primary operation fails.
            return "{\"_error\":\"could not serialise\"}";
        }
    }
}
