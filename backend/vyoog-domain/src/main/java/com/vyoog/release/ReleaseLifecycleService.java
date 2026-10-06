package com.vyoog.release;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.platform.audit.AuditService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0928: moving a release through PLANNED, OPEN, FROZEN, RELEASED (and FROZEN back to OPEN).
 *
 * <p>Each move is recorded ({@code release_transition}) and audited. Two forward moves are guarded by the readiness
 * gates an administrator has switched on ({@link ReleaseGateConfigService}): OPEN to FROZEN and FROZEN to RELEASED.
 * If a gate fails the move is refused (409, naming each failing gate), unless the caller overrides it with a written
 * reason; an override records the reason and the gates that were failing. A reopen (FROZEN to OPEN) also needs a
 * reason. The scope of a release can change only while it is PLANNED or OPEN ({@link ReleaseService}).
 *
 * <p>Who may do it is the caller's rule (Approver, matrix "Baseline"). Signing a release off with step-up is VYB-0929.
 */
@Service
public class ReleaseLifecycleService {

    public record GateResult(ReleaseGate gate, boolean passed, String detail) {}

    /** A move available from the release's current state, with each enabled gate evaluated now. */
    public record Offered(ReleaseState to, boolean needsReason, List<GateResult> gates) {
        public boolean ready() { return gates.stream().allMatch(GateResult::passed); }
    }

    public record Transition(UUID id, ReleaseState from, ReleaseState to, String reason, boolean overridden,
                              List<ReleaseGateException.Failed> failedGates, UUID changedBy, Instant changedAt) {}

    private final ReleaseRepository releases;
    private final ReleaseService releaseService;
    private final ReleaseGateConfigService gateConfig;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper json;

    public ReleaseLifecycleService(ReleaseRepository releases, ReleaseService releaseService, ReleaseGateConfigService gateConfig,
                                    JdbcTemplate jdbc, AuditService audit, ObjectMapper json) {
        this.releases = releases;
        this.releaseService = releaseService;
        this.gateConfig = gateConfig;
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
    }

    private static ReleaseGateConfigService.Transition guarded(ReleaseState from, ReleaseState to) {
        if (from == ReleaseState.OPEN && to == ReleaseState.FROZEN) return ReleaseGateConfigService.Transition.OPEN_TO_FROZEN;
        if (from == ReleaseState.FROZEN && to == ReleaseState.RELEASED) return ReleaseGateConfigService.Transition.FROZEN_TO_RELEASED;
        return null;
    }

    /** The moves available now, each with its enabled gates evaluated: what a screen shows before anyone presses a button. */
    public List<Offered> options(UUID releaseId) {
        Release release = releases.findById(releaseId).orElseThrow(() -> new NoSuchElementException("No such release: " + releaseId));
        List<Offered> out = new ArrayList<>();
        for (ReleaseState to : ReleaseState.values()) {
            if (!release.getState().canMoveTo(to)) continue;
            out.add(new Offered(to, release.getState() == ReleaseState.FROZEN && to == ReleaseState.OPEN,
                evaluate(releaseId, guarded(release.getState(), to))));
        }
        return out;
    }

    @Transactional
    public Release transition(UUID releaseId, ReleaseState to, String reason, boolean override, UUID actorId) {
        // Serialise concurrent moves of one release; the state is read under the lock.
        List<String> locked = jdbc.queryForList("SELECT state FROM release WHERE id = ? FOR UPDATE", String.class, releaseId);
        if (locked.isEmpty()) throw new NoSuchElementException("No such release: " + releaseId);
        Release release = releases.findById(releaseId).orElseThrow();
        ReleaseState from = release.getState();
        if (to == null || !from.canMoveTo(to)) {
            throw new IllegalStateException("A release cannot go from " + from + " to " + to);
        }
        String cleanReason = reason == null || reason.isBlank() ? null : reason.strip();
        if (from == ReleaseState.FROZEN && to == ReleaseState.OPEN && cleanReason == null) {
            throw new IllegalArgumentException("Reopening a frozen release needs a reason");
        }

        List<ReleaseGateException.Failed> failed = evaluate(releaseId, guarded(from, to)).stream()
            .filter(g -> !g.passed()).map(g -> new ReleaseGateException.Failed(g.gate(), g.detail())).toList();
        boolean overridden = false;
        if (!failed.isEmpty()) {
            if (!override) throw new ReleaseGateException(failed);
            if (cleanReason == null) {
                throw new IllegalArgumentException("Proceeding past failing readiness gates needs a reason");
            }
            overridden = true;
        }

        release.setState(to);
        releases.save(release);
        jdbc.update("""
            INSERT INTO release_transition (release_id, from_state, to_state, reason, overridden, failed_gates, changed_by)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
            """, releaseId, from.name(), to.name(), cleanReason, overridden, overridden ? asJson(failed) : null, actorId);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("state", from.name());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("state", to.name());
        after.put("reason", cleanReason);
        after.put("overridden", overridden);
        if (overridden) after.put("failedGates", failed.stream().map(f -> f.gate().name()).toList());
        audit.record(actorId, "release.transitioned", "RELEASE", releaseId, before, after);
        return release;
    }

    public List<Transition> history(UUID releaseId) {
        if (!releases.existsById(releaseId)) throw new NoSuchElementException("No such release: " + releaseId);
        return jdbc.query("""
            SELECT id, from_state, to_state, reason, overridden, failed_gates::text AS failed_gates, changed_by, changed_at
              FROM release_transition WHERE release_id = ? ORDER BY changed_at, id
            """, (rs, i) -> new Transition(rs.getObject("id", UUID.class), ReleaseState.valueOf(rs.getString("from_state")),
                ReleaseState.valueOf(rs.getString("to_state")), rs.getString("reason"), rs.getBoolean("overridden"),
                parseFailed(rs.getString("failed_gates")), rs.getObject("changed_by", UUID.class),
                rs.getTimestamp("changed_at").toInstant()), releaseId);
    }

    // ----------------------------------------------------------------- gates

    /** Every enabled gate for the transition, evaluated against the release's committed scope now; none for an unguarded move. */
    List<GateResult> evaluate(UUID releaseId, ReleaseGateConfigService.Transition transition) {
        if (transition == null) return List.of();
        List<GateResult> out = new ArrayList<>();
        List<UUID> scope = releaseService.scope(releaseId);
        for (ReleaseGateConfigService.Setting s : gateConfig.enabledFor(transition)) {
            out.add(switch (s.gate()) {
                case SCOPE_NOT_EMPTY -> scope.isEmpty()
                    ? new GateResult(s.gate(), false, "No requirement is committed to this release")
                    : new GateResult(s.gate(), true, scope.size() + " committed");
                case ALL_APPROVED -> allApproved(releaseId, scope);
                case NO_CRITICAL_GAPS -> {
                    int n = releaseService.readiness(releaseId).criticalOpenGaps();
                    yield n == 0 ? new GateResult(s.gate(), true, "No open critical gaps")
                        : new GateResult(s.gate(), false, n + " open critical gap" + (n == 1 ? "" : "s") + " on committed requirements");
                }
                case NO_BLOCKED_ITEMS -> {
                    int n = releaseService.blocked(releaseId).size();
                    yield n == 0 ? new GateResult(s.gate(), true, "Nothing committed is blocked")
                        : new GateResult(s.gate(), false, n + " committed requirement" + (n == 1 ? " is" : "s are")
                            + " blocked (unverified, conflicting or unowned)");
                }
                case VERIFIED_SHARE -> {
                    int needed = s.threshold() == null ? 100 : s.threshold();
                    if (scope.isEmpty()) yield new GateResult(s.gate(), false, "Nothing is committed, so nothing is verified");
                    long pct = Math.round(releaseService.readiness(releaseId).verifiedRatio() * 100);
                    yield pct >= needed ? new GateResult(s.gate(), true, pct + "% verified")
                        : new GateResult(s.gate(), false, pct + "% of committed requirements are verified; at least " + needed + "% is required");
                }
            });
        }
        return out;
    }

    private GateResult allApproved(UUID releaseId, List<UUID> scope) {
        if (scope.isEmpty()) return new GateResult(ReleaseGate.ALL_APPROVED, true, "Nothing is committed");
        String placeholders = String.join(",", scope.stream().map(i -> "?").toList());
        List<String> notApproved = jdbc.queryForList(("SELECT key FROM requirement WHERE id IN (%s) AND status <> 'APPROVED' ORDER BY key")
            .formatted(placeholders), String.class, scope.toArray());
        if (notApproved.isEmpty()) return new GateResult(ReleaseGate.ALL_APPROVED, true, "All " + scope.size() + " committed requirements are Approved");
        String shown = String.join(", ", notApproved.subList(0, Math.min(5, notApproved.size())));
        return new GateResult(ReleaseGate.ALL_APPROVED, false, notApproved.size() + " of " + scope.size()
            + " committed requirements are not Approved (" + shown + (notApproved.size() > 5 ? ", ..." : "") + ")");
    }

    private String asJson(List<ReleaseGateException.Failed> failed) {
        try {
            return json.writeValueAsString(failed.stream().map(f -> Map.of("gate", f.gate().name(), "detail", f.detail())).toList());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not record the failing gates", e);
        }
    }

    private List<ReleaseGateException.Failed> parseFailed(String raw) {
        if (raw == null) return List.of();
        try {
            List<Map<String, String>> rows = json.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            return rows.stream().map(m -> new ReleaseGateException.Failed(ReleaseGate.valueOf(m.get("gate")), m.get("detail"))).toList();
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
