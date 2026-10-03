package com.vyoog.integration.connector;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * VYB-0913: reads and writes {@code connector_sync_log} (V039). Every write is its own transaction
 * ({@code REQUIRES_NEW}), so the log is true even when the caller that asked for the send is itself
 * inside a transaction that later rolls back, and so a claim on an idempotency key is visible to
 * other instances at once, not when the caller finally commits.
 */
@Component
public class ConnectorSyncLog {

    public enum ClaimKind { CLAIMED, ALREADY_SUCCEEDED, IN_PROGRESS }

    public record Claim(ClaimKind kind, UUID id) {}

    public record Entry(UUID id, String connectionKey, String operation, String idempotencyKey, String status,
                        int attempts, Integer httpStatus, String error, int payloadBytes, String payloadSha256,
                        Instant startedAt, Instant finishedAt) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ConnectorSyncLog(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Takes the right to send this operation, atomically. An earlier IN_PROGRESS claim older than
     * {@code staleAfter} was left by an instance that stopped mid-send; it is marked FAILED first so it
     * cannot block the key forever.
     */
    public Claim claim(String connectionKey, String operation, String idempotencyKey, byte[] payload, Duration staleAfter) {
        Claim claim = claimOnce(connectionKey, operation, idempotencyKey, payload, staleAfter);
        // a holder that finished as FAILED between our two statements has freed the key: try once more
        return claim.id() == null ? claimOnce(connectionKey, operation, idempotencyKey, payload, staleAfter) : claim;
    }

    private Claim claimOnce(String connectionKey, String operation, String idempotencyKey, byte[] payload, Duration staleAfter) {
        return tx.execute(status -> {
            jdbc.update("""
                UPDATE connector_sync_log
                   SET status = 'FAILED', finished_at = clock_timestamp(),
                       error = 'abandoned: the instance sending this stopped before it finished'
                 WHERE connection_key = ? AND idempotency_key = ? AND status = 'IN_PROGRESS'
                   AND started_at < clock_timestamp() - (? * interval '1 millisecond')
                """, connectionKey, idempotencyKey, staleAfter.toMillis());

            List<UUID> inserted = jdbc.query("""
                INSERT INTO connector_sync_log (connection_key, operation, idempotency_key, status, payload_bytes, payload_sha256)
                VALUES (?, ?, ?, 'IN_PROGRESS', ?, ?)
                ON CONFLICT (connection_key, idempotency_key) WHERE status IN ('IN_PROGRESS', 'SUCCEEDED') DO NOTHING
                RETURNING id
                """, (rs, n) -> rs.getObject("id", UUID.class),
                connectionKey, operation, idempotencyKey, payload.length, sha256(payload));
            if (!inserted.isEmpty()) return new Claim(ClaimKind.CLAIMED, inserted.get(0));

            return jdbc.query("""
                SELECT id, status FROM connector_sync_log
                 WHERE connection_key = ? AND idempotency_key = ? AND status IN ('IN_PROGRESS', 'SUCCEEDED')
                 ORDER BY started_at DESC LIMIT 1
                """, (rs, n) -> new Claim("SUCCEEDED".equals(rs.getString("status"))
                    ? ClaimKind.ALREADY_SUCCEEDED : ClaimKind.IN_PROGRESS, rs.getObject("id", UUID.class)),
                connectionKey, idempotencyKey).stream().findFirst()
                .orElseGet(() -> new Claim(ClaimKind.IN_PROGRESS, null));
        });
    }

    public void finish(UUID id, boolean succeeded, int attempts, Integer httpStatus, String error) {
        tx.executeWithoutResult(status -> jdbc.update("""
            UPDATE connector_sync_log
               SET status = ?, attempts = ?, http_status = ?, error = ?, finished_at = clock_timestamp()
             WHERE id = ?
            """, succeeded ? "SUCCEEDED" : "FAILED", attempts, httpStatus, error, id));
    }

    /** Newest first. */
    public List<Entry> recent(String connectionKey, int limit) {
        return jdbc.query("""
            SELECT * FROM connector_sync_log WHERE connection_key = ? ORDER BY started_at DESC LIMIT ?
            """, (rs, n) -> new Entry(rs.getObject("id", UUID.class), rs.getString("connection_key"),
                rs.getString("operation"), rs.getString("idempotency_key"), rs.getString("status"),
                rs.getInt("attempts"), (Integer) rs.getObject("http_status"), rs.getString("error"),
                rs.getInt("payload_bytes"), rs.getString("payload_sha256"),
                instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at"))),
            connectionKey, limit);
    }

    public Optional<Instant> lastSuccess(String connectionKey) {
        return jdbc.query("""
            SELECT max(finished_at) AS at FROM connector_sync_log WHERE connection_key = ? AND status = 'SUCCEEDED'
            """, (rs, n) -> instant(rs.getTimestamp("at")), connectionKey).stream()
            .filter(java.util.Objects::nonNull).findFirst();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
