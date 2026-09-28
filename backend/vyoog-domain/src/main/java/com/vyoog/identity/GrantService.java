package com.vyoog.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Answers "does this grant cover that capability" — a PLATFORM grant covers
 * everything, a PRODUCT grant covers every application and capability beneath it, an
 * APP grant covers every capability beneath it, and a CAPABILITY grant covers just
 * that one. This walk (capability &rarr; application &rarr; product) is four rows
 * joined once, not a recursive query — the portfolio hierarchy is exactly three
 * levels deep by design (VYB-0100), so there's no unbounded depth to recurse over.
 */
@Service
public class GrantService {

    private final JdbcTemplate jdbc;

    public GrantService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SCOPE_MATCH = """
        (
          ag.scope_type = 'PLATFORM'
          OR (ag.scope_type = 'PRODUCT'    AND ag.scope_id = a.product_id)
          OR (ag.scope_type = 'APP'        AND ag.scope_id = c.application_id)
          OR (ag.scope_type = 'CAPABILITY' AND ag.scope_id = c.id)
        )
        """;

    /** VYB-0304/0343: does this user currently hold this role over this capability. */
    public boolean holds(UUID userId, AccessRole role, UUID capabilityId) {
        if (capabilityId == null) return false;
        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM access_grant ag
            JOIN capability c ON c.id = ?
            JOIN application a ON a.id = c.application_id
            WHERE ag.user_id = ? AND ag.role = ?
              AND ag.revoked_at IS NULL AND (ag.expires_at IS NULL OR ag.expires_at > now())
              AND %s
            """.formatted(SCOPE_MATCH), Integer.class,
            capabilityId, userId, role.name());
        return count != null && count > 0;
    }

    /** VYB-0373: a platform-wide grant, unscoped to any one product/app/capability. */
    public boolean holdsPlatform(UUID userId, AccessRole role) {
        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM access_grant
            WHERE user_id = ? AND role = ? AND scope_type = 'PLATFORM'
              AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())
            """, Integer.class, userId, role.name());
        return count != null && count > 0;
    }

    /** VYB-0343: every approver whose grant covers a requirement's capability. */
    public List<UUID> usersHolding(AccessRole role, UUID capabilityId) {
        if (capabilityId == null) return List.of();
        return jdbc.queryForList("""
            SELECT DISTINCT ag.user_id FROM access_grant ag
            JOIN capability c ON c.id = ?
            JOIN application a ON a.id = c.application_id
            WHERE ag.role = ?
              AND ag.revoked_at IS NULL AND (ag.expires_at IS NULL OR ag.expires_at > now())
              AND %s
            """.formatted(SCOPE_MATCH), UUID.class,
            capabilityId, role.name());
    }
}
