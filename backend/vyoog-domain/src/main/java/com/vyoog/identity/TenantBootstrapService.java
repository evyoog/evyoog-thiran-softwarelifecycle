package com.vyoog.identity;

import com.vyoog.platform.audit.AuditService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0730: a one-time operation — the detector rules are already seeded by the
 * migrations themselves (AC2; every {@code gap_rule_template} row has existed since
 * the phase that introduced it, not created here), so the only real step left is
 * granting the first administrator. {@code app_config.bootstrapped_at} (V008) is what
 * stops this running a second time and handing out a second "first" administrator —
 * checked and set inside the same transaction as the grant (AC1), so a failure after
 * the grant but before the flag would be rolled back entirely, not left half-done.
 *
 * <p>"Default roles" (the rest of AC1's phrase) has nothing to seed beyond the nine
 * {@link AccessRole} values themselves, which are a fixed enum, not configurable data
 * — there is no per-tenant role catalogue to populate.
 */
@Service
public class TenantBootstrapService {

    private final AccessGrantService grants;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public TenantBootstrapService(AccessGrantService grants, JdbcTemplate jdbc, AuditService audit) {
        this.grants = grants;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public boolean isBootstrapped() {
        return jdbc.queryForObject("SELECT bootstrapped_at IS NOT NULL FROM app_config WHERE id = 1", Boolean.class);
    }

    @Transactional
    public void bootstrap(UUID firstAdministratorUserId, UUID actor) {
        if (isBootstrapped()) {
            throw new IllegalStateException("This deployment is already bootstrapped");
        }
        grants.grant(firstAdministratorUserId, AccessRole.ADMINISTRATOR, ScopeType.PLATFORM, null, null, actor);
        // Session 14: a bare Instant here throws PSQLException at real-database time
        // (pgjdbc's 2-arg setObject can't infer a SQL type for it) — same root cause,
        // same fix, as AuditService.record's own bug. This one-time path had never run
        // against a real Postgres before this session either.
        jdbc.update("UPDATE app_config SET bootstrapped_at = ? WHERE id = 1", Timestamp.from(Instant.now()));
        audit.record(actor, "tenant.bootstrapped", "APP_CONFIG", null, null,
            Map.of("firstAdministrator", firstAdministratorUserId.toString()));
    }
}
