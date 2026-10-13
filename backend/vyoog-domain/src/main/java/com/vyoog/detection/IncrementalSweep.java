package com.vyoog.detection;

import com.vyoog.ai.AiUsageTracker;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0960 (D48): the nightly sweep of a detector that compares requirements with one another (possible duplicates and
 * contradictions: {@link Detector#sweepsChangedOnly}).
 *
 * <p>The whole-database comparison it replaces grows with the square of the number of requirements: 29 s at 2,000 and not finished
 * after 20 minutes at 50,000 (about five hours extrapolated). This checks only the requirements whose embedding is new or changed since
 * its last pass, each with the same exact one-requirement check a write makes ({@link Detector#scanOne}, about 0.2 s at 50,000), and
 * reconciles each in its own small transaction. A pair is found from either of its two sides, so checking whichever one changed is
 * enough; a pair that stopped being similar is resolved from either side too (see {@code finding.related_object_id}).
 *
 * <p>Where it has got to is kept as the position {@code (embedded_at, requirement id)} of the last requirement checked, per rule
 * ({@code detection_sweep_state}), so a pass that runs out of time (the sweep's budget) carries on the next night instead of starting
 * again, and a night with few changes is short. The first pass on a database that has never been swept checks everything and may take
 * several nights. The position is reset, and everything checked again, when the rule's threshold changes. A rule that is switched off
 * or not configured advances nothing, so what accumulated while it was off is checked when it comes back.
 *
 * <p>Not covered by this and still the job of the write path: a requirement moved to another capability keeps its embedding and so is
 * not "changed" here, but the edit that moved it re-checks it at once.
 */
@Service
public class IncrementalSweep {

    private static final Logger log = LoggerFactory.getLogger(IncrementalSweep.class);
    static final int PAGE = 200;

    private final JdbcTemplate jdbc;
    private final FindingReconciler reconciler;
    private final GapRuleService gapRules;
    private final AiUsageTracker aiUsage;

    public IncrementalSweep(JdbcTemplate jdbc, FindingReconciler reconciler, GapRuleService gapRules, AiUsageTracker aiUsage) {
        this.jdbc = jdbc;
        this.reconciler = reconciler;
        this.gapRules = gapRules;
        this.aiUsage = aiUsage;
    }

    /** A requirement whose embedding is new or changed, at the moment it was embedded. */
    public record Changed(UUID requirementId, Instant embeddedAt) {}

    /** Where the rule's sweep has got to. {@code atStart} is true when nothing has been checked under the current settings. */
    public record Position(Instant at, UUID id, String settingsHash) {
        boolean atStart() { return at == null; }
    }

    /**
     * @param processed requirements checked in this pass
     * @param stoppedEarly true if the pass ran out of time or AI budget with requirements still waiting; they are first next time
     */
    public record Outcome(FindingReconciler.ReconcileResult totals, int processed, boolean stoppedEarly, boolean reset) {}

    public Position position(String ruleKey) {
        List<Position> rows = jdbc.query("SELECT cursor_at, cursor_id, settings_hash FROM detection_sweep_state WHERE rule_key = ?",
            (rs, n) -> new Position(rs.getTimestamp("cursor_at") == null ? null : rs.getTimestamp("cursor_at").toInstant(),
                rs.getObject("cursor_id", UUID.class), rs.getString("settings_hash")), ruleKey);
        return rows.isEmpty() ? new Position(null, null, null) : rows.get(0);
    }

    /** The next requirements after a position, oldest embedding first. */
    public List<Changed> changedAfter(Position after, int limit) {
        if (after.atStart()) {
            return jdbc.query("SELECT requirement_id, embedded_at FROM requirement_embedding ORDER BY embedded_at, requirement_id LIMIT ?",
                (rs, n) -> new Changed(rs.getObject("requirement_id", UUID.class), rs.getTimestamp("embedded_at").toInstant()), limit);
        }
        return jdbc.query("""
            SELECT requirement_id, embedded_at FROM requirement_embedding
             WHERE (embedded_at, requirement_id) > (?, ?) ORDER BY embedded_at, requirement_id LIMIT ?
            """,
            (rs, n) -> new Changed(rs.getObject("requirement_id", UUID.class), rs.getTimestamp("embedded_at").toInstant()),
            Timestamp.from(after.at()), after.id(), limit);
    }

    /** How many requirements are waiting to be checked for this rule (for the load rehearsal and an operator's question). */
    public long waiting(String ruleKey) {
        Position p = position(ruleKey);
        if (p.atStart()) return jdbc.queryForObject("SELECT count(*) FROM requirement_embedding", Long.class);
        return jdbc.queryForObject("SELECT count(*) FROM requirement_embedding WHERE (embedded_at, requirement_id) > (?, ?)",
            Long.class, Timestamp.from(p.at()), p.id());
    }

    private String settingsOf(Detector d) {
        return String.valueOf(gapRules.getThreshold(d.ruleKey()));
    }

    public Outcome run(Detector d, Instant deadline) {
        String rule = d.ruleKey();
        String settings = settingsOf(d);
        Position position = position(rule);
        boolean reset = false;
        if (!Objects.equals(position.settingsHash(), settings)) {
            // The settings the earlier checks were made under are not these: check everything again under these.
            reset = position.settingsHash() != null;
            position = new Position(null, null, settings);
            save(rule, position, 0, false);
            if (reset) log.info("[detection] {}: its settings changed, so every requirement will be checked again", rule);
        }

        int processed = 0;
        int opened = 0, refreshed = 0, reopened = 0, resolved = 0;
        boolean stoppedEarly = false;
        try {
            outer:
            while (true) {
                List<Changed> page = changedAfter(position, PAGE);
                if (page.isEmpty()) break;
                for (Changed c : page) {
                    if (Instant.now().isAfter(deadline)) { stoppedEarly = true; break outer; }
                    if (d.callsModel() && aiUsage.used() >= aiUsage.limit()) { stoppedEarly = true; break outer; } // VYB-0620: out of AI calls; the rest wait
                    var r = reconciler.reconcileOne(rule, c.requirementId(), d.scanOne(c.requirementId()));
                    opened += r.opened(); refreshed += r.refreshed(); reopened += r.reopened(); resolved += r.resolved();
                    processed++;
                    position = new Position(c.embeddedAt(), c.requirementId(), settings);
                }
                save(rule, position, processed, false);
            }
        } finally {
            // Whatever was checked stays checked, even if the next one threw (a provider that is down): that is what "carry on" means.
            if (processed > 0) save(rule, position, processed, !stoppedEarly);
        }
        if (processed == 0) save(rule, position, 0, !stoppedEarly);
        return new Outcome(new FindingReconciler.ReconcileResult(rule, opened, refreshed, reopened, resolved, false), processed, stoppedEarly, reset);
    }

    private void save(String rule, Position p, int processed, boolean finished) {
        jdbc.update("""
            INSERT INTO detection_sweep_state (rule_key, cursor_at, cursor_id, settings_hash, processed_last, finished_at, updated_at)
            VALUES (?, ?, ?, ?, ?, CASE WHEN ? THEN now() END, now())
            ON CONFLICT (rule_key) DO UPDATE
               SET cursor_at = EXCLUDED.cursor_at, cursor_id = EXCLUDED.cursor_id, settings_hash = EXCLUDED.settings_hash,
                   processed_last = EXCLUDED.processed_last,
                   finished_at = CASE WHEN ? THEN now() ELSE detection_sweep_state.finished_at END, updated_at = now()
            """, rule, p.at() == null ? null : Timestamp.from(p.at()), p.id(), p.settingsHash(), processed, finished, finished);
    }
}
