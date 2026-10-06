package com.vyoog.release;

import com.vyoog.platform.audit.AuditService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0928: which readiness gates guard each guarded release transition, platform-wide. Rows are seeded by V045 and
 * only ever updated (a gate is switched on or off, or the verified-share threshold changed), never added or removed.
 * Editing is an administrator's decision and every edit is audited with before and after.
 */
@Service
public class ReleaseGateConfigService {

    public enum Transition { OPEN_TO_FROZEN, FROZEN_TO_RELEASED }

    public record Setting(Transition transition, ReleaseGate gate, boolean enabled, Integer threshold) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ReleaseGateConfigService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public List<Setting> list() {
        return jdbc.query("SELECT transition, gate, enabled, threshold FROM release_gate ORDER BY transition DESC, gate", (rs, i) ->
            new Setting(Transition.valueOf(rs.getString("transition")), ReleaseGate.valueOf(rs.getString("gate")),
                rs.getBoolean("enabled"), (Integer) rs.getObject("threshold")));
    }

    /** The gates switched on for one transition, in a stable order. */
    public List<Setting> enabledFor(Transition transition) {
        return list().stream().filter(s -> s.transition() == transition && s.enabled()).toList();
    }

    /**
     * {@code threshold} applies to VERIFIED_SHARE only (0 to 100); omitted, the current one is kept. A threshold on any
     * other gate is refused rather than ignored.
     */
    @Transactional
    public Setting update(Transition transition, ReleaseGate gate, boolean enabled, Integer threshold, UUID actorId) {
        Setting before = list().stream().filter(s -> s.transition() == transition && s.gate() == gate).findFirst()
            .orElseThrow(() -> new NoSuchElementException("No such gate: " + transition + "/" + gate));
        if (gate != ReleaseGate.VERIFIED_SHARE && threshold != null) {
            throw new IllegalArgumentException("Only the verified-share gate has a threshold");
        }
        if (threshold != null && (threshold < 0 || threshold > 100)) {
            throw new IllegalArgumentException("The threshold is a percentage from 0 to 100");
        }
        Integer effective = gate == ReleaseGate.VERIFIED_SHARE ? (threshold != null ? threshold : before.threshold()) : null;
        jdbc.update("UPDATE release_gate SET enabled = ?, threshold = ? WHERE transition = ? AND gate = ?",
            enabled, effective, transition.name(), gate.name());
        audit.record(actorId, "release-gate.updated", "RELEASE_GATE", null, describe(before),
            describe(new Setting(transition, gate, enabled, effective)));
        return new Setting(transition, gate, enabled, effective);
    }

    private static Map<String, Object> describe(Setting s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transition", s.transition().name());
        m.put("gate", s.gate().name());
        m.put("enabled", s.enabled());
        m.put("threshold", s.threshold());
        return m;
    }
}
