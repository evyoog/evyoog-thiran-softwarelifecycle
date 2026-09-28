package com.vyoog.platform;

import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0132: a repeated request carrying the same {@code Idempotency-Key} returns the
 * first response instead of creating a second row. Generic across endpoints (each
 * caller supplies its own {@code endpoint} label) rather than one-off per feature.
 */
@Service
public class IdempotencyService {

    private final JdbcTemplate jdbc;

    public IdempotencyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> find(String key, String endpoint) {
        return jdbc.query(
            "SELECT response_id FROM idempotency_key WHERE key = ? AND endpoint = ?",
            (rs, n) -> UUID.fromString(rs.getString("response_id")), key, endpoint)
            .stream().findFirst();
    }

    /**
     * @return true if this call actually recorded the key; false if another concurrent
     *     request with the same key won the race — the caller should then re-{@link #find}
     *     rather than treat its own result as authoritative.
     */
    public boolean record(String key, String endpoint, UUID responseId) {
        try {
            jdbc.update(
                "INSERT INTO idempotency_key (key, endpoint, response_id) VALUES (?, ?, ?)",
                key, endpoint, responseId);
            return true;
        } catch (DataIntegrityViolationException raceLost) {
            return false;
        }
    }
}
