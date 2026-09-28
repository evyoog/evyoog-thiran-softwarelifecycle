package com.vyoog.platform.audit;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only (VYB-0125, VYB-0720). The database itself refuses UPDATE/DELETE on this
 * table (see the trigger in V001__baseline.sql) — this class has no setters at all, so
 * there is no code path that would even try.
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_type", nullable = false)
    private String actorType = "USER";

    @Column(nullable = false)
    private String action;

    @Column(name = "object_type")
    private String objectType;

    @Column(name = "object_id")
    private UUID objectId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String before;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String after;

    @Column(name = "request_id")
    private String requestId;

    /**
     * VYB-0721: the caller's origin address. Written via a raw {@code ?::inet} cast in
     * {@link AuditService} rather than through this entity's own (now unused for
     * writes) constructors — Postgres's {@code inet} type is exactly the kind of thing
     * this codebase already routes through JdbcTemplate instead of teaching Hibernate
     * a new column type for (see pgvector's {@code embedding} column). Reading it back
     * through this JPA entity works as plain text on the wire either way, but
     * {@code ddl-auto: validate} compares JDBC type codes against the real column,
     * not wire format — a bare String field reports VARCHAR and fails validation
     * against an actual {@code inet} column. {@code SqlTypes.INET} (Hibernate's own
     * Postgres-specific network-address mapping) tells the validator what's really
     * there without giving this field a converter or changing how it reads/writes.
     */
    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "ip")
    private String ip;

    protected AuditEvent() {}

    public AuditEvent(UUID actorId, String action, String objectType, UUID objectId,
                       String before, String after, String requestId) {
        this(actorId, "USER", action, objectType, objectId, before, after, requestId);
    }

    /**
     * VYB-0313/0356: some events are caused by the system itself or by a service
     * account (CI ingesting a test run), not a person — {@code actor_type} says which,
     * so the audit trail doesn't imply a human did something they didn't.
     */
    public AuditEvent(UUID actorId, String actorType, String action, String objectType, UUID objectId,
                       String before, String after, String requestId) {
        this.actorId = actorId;
        this.actorType = actorType;
        this.action = action;
        this.objectType = objectType;
        this.objectId = objectId;
        this.before = before;
        this.after = after;
        this.requestId = requestId;
    }

    public UUID getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public UUID getActorId() { return actorId; }
    public String getActorType() { return actorType; }
    public String getAction() { return action; }
    public String getObjectType() { return objectType; }
    public UUID getObjectId() { return objectId; }
    public String getBefore() { return before; }
    public String getAfter() { return after; }
    public String getRequestId() { return requestId; }
    public String getIp() { return ip; }
}
