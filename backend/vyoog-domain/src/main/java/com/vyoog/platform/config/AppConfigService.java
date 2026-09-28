package com.vyoog.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0734: the one {@code app_config} row, read and written from one place — every
 * other service that reads a single column of it directly (AiUsageTracker,
 * EmbeddingService, DetectorQualityService, RequirementKeyAllocator) predates this and
 * is left alone rather than churned for its own sake; this exists for the settings the
 * Administration screen actually needs to show and change as a set (VYB-0757), plus
 * the few values Phase 5's own new services need (VYB-0702/0712/0713/0723/0731).
 */
@Service
public class AppConfigService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AppConfigService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record AppConfigView(
        String reqKeyPrefix, Map<String, Integer> stageStallThresholdDays,
        int maxExternalGrantDays, int staleKeyAgeDays, int keyRotationOverlapDays,
        int auditRetentionDays, boolean suspended, String suspendedReason,
        String embeddingModel, int aiCallsPerRunLimit, BigDecimal noisyDetectorDismissalCeiling) {}

    public AppConfigView current() {
        return jdbc.queryForObject("""
            SELECT req_key_prefix, stage_stall_threshold_days::text AS thresholds,
                   max_external_grant_days, stale_key_age_days, key_rotation_overlap_days,
                   audit_retention_days, suspended, suspended_reason,
                   embedding_model, ai_calls_per_run_limit, noisy_detector_dismissal_ceiling
            FROM app_config WHERE id = 1
            """,
            (rs, n) -> new AppConfigView(
                rs.getString("req_key_prefix"), readThresholds(rs.getString("thresholds")),
                rs.getInt("max_external_grant_days"), rs.getInt("stale_key_age_days"),
                rs.getInt("key_rotation_overlap_days"), rs.getInt("audit_retention_days"),
                rs.getBoolean("suspended"), rs.getString("suspended_reason"),
                rs.getString("embedding_model"), rs.getInt("ai_calls_per_run_limit"),
                rs.getBigDecimal("noisy_detector_dismissal_ceiling")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> readThresholds(String jsonText) {
        try {
            return json.readValue(jsonText, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** VYB-0734 AC1: changes only what {@link com.vyoog.requirements.RequirementKeyAllocator#next} reads next time — an existing key is a stored string, untouched. */
    public void setReqKeyPrefix(String prefix) {
        jdbc.update("UPDATE app_config SET req_key_prefix = ? WHERE id = 1", prefix);
    }

    public void setStageStallThresholds(Map<String, Integer> thresholds) {
        try {
            jdbc.update("UPDATE app_config SET stage_stall_threshold_days = ?::jsonb WHERE id = 1",
                json.writeValueAsString(thresholds));
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not encode thresholds: " + e.getMessage());
        }
    }

    public void setMaxExternalGrantDays(int days) {
        jdbc.update("UPDATE app_config SET max_external_grant_days = ? WHERE id = 1", days);
    }

    public void setStaleKeyAgeDays(int days) {
        jdbc.update("UPDATE app_config SET stale_key_age_days = ? WHERE id = 1", days);
    }

    public void setKeyRotationOverlapDays(int days) {
        jdbc.update("UPDATE app_config SET key_rotation_overlap_days = ? WHERE id = 1", days);
    }

    public void setAuditRetentionDays(int days) {
        jdbc.update("UPDATE app_config SET audit_retention_days = ? WHERE id = 1", days);
    }

    /** VYB-0731: authentication still succeeds (this never touches SecurityConfig); every other request is refused with {@code reason}. */
    public void suspend(String reason) {
        jdbc.update("UPDATE app_config SET suspended = true, suspended_reason = ? WHERE id = 1", reason);
    }

    public void resume() {
        jdbc.update("UPDATE app_config SET suspended = false, suspended_reason = NULL WHERE id = 1");
    }

    public boolean isSuspended() {
        Boolean s = jdbc.queryForObject("SELECT suspended FROM app_config WHERE id = 1", Boolean.class);
        return Boolean.TRUE.equals(s);
    }

    public String suspendedReason() {
        return jdbc.queryForObject("SELECT suspended_reason FROM app_config WHERE id = 1", String.class);
    }

    public int maxExternalGrantDays() {
        return jdbc.queryForObject("SELECT max_external_grant_days FROM app_config WHERE id = 1", Integer.class);
    }

    public int staleKeyAgeDays() {
        return jdbc.queryForObject("SELECT stale_key_age_days FROM app_config WHERE id = 1", Integer.class);
    }

    public int keyRotationOverlapDays() {
        return jdbc.queryForObject("SELECT key_rotation_overlap_days FROM app_config WHERE id = 1", Integer.class);
    }

    public int auditRetentionDays() {
        return jdbc.queryForObject("SELECT audit_retention_days FROM app_config WHERE id = 1", Integer.class);
    }

    /**
     * VYB-0334/0372 (session 14): {@code ClarificationService.escalateAgeing} has read
     * this column directly since session 9 (V005); exposing it here too is what lets
     * the calendar (session 14) compute "due by" without duplicating that raw query.
     */
    public int clarificationEscalationDays() {
        return jdbc.queryForObject("SELECT clarification_escalation_days FROM app_config WHERE id = 1", Integer.class);
    }

    /**
     * VYB-0757 (session 16): these three columns have existed since V006 and were
     * always readable via {@link #current()} — but with no setter, "editable inline"
     * on the Settings screen was never actually possible for them, only for the four
     * fields above. Same one-column-one-statement pattern as those.
     */
    public void setNoisyDetectorDismissalCeiling(BigDecimal ceiling) {
        jdbc.update("UPDATE app_config SET noisy_detector_dismissal_ceiling = ? WHERE id = 1", ceiling);
    }

    public void setAiCallsPerRunLimit(int limit) {
        jdbc.update("UPDATE app_config SET ai_calls_per_run_limit = ? WHERE id = 1", limit);
    }

    public void setEmbeddingModel(String model) {
        jdbc.update("UPDATE app_config SET embedding_model = ? WHERE id = 1", model);
    }
}
